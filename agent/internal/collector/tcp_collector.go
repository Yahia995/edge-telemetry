package collector

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"time"

	"github.com/Yahia995/edge-telemetry/agent/internal/ebpf"
	pb "github.com/Yahia995/edge-telemetry/agent/proto/telemetry"
	log "github.com/sirupsen/logrus"
)

var tcpStateNames = map[uint8]string{
	1:  "ESTABLISHED",
	2:  "SYN_SENT",
	3:  "SYN_RECV",
	4:  "FIN_WAIT1",
	5:  "FIN_WAIT2",
	6:  "TIME_WAIT",
	7:  "CLOSE",
	8:  "CLOSE_WAIT",
	9:  "LAST_ACK",
	10: "LISTEN",
	11: "CLOSING",
}

func stateName(s uint8) string {
	if n, ok := tcpStateNames[s]; ok {
		return n
	}
	return fmt.Sprintf("UNKNOWN(%d)", s)
}

type TcpCollector struct {
	loader    *ebpf.Loader
	pollTimeout time.Duration
}

func NewTcpCollector(bpfObjectPath string) (*TcpCollector, error) {
	loader, err := ebpf.Open(bpfObjectPath)
	if err != nil {
		return nil, fmt.Errorf("ebpf.Open: %w", err)
	}
	return &TcpCollector{
		loader:      loader,
		pollTimeout: 100 * time.Millisecond,
	}, nil
}

func (t *TcpCollector) Run(
	ctx      context.Context,
	deviceID string,
	out      chan<- *pb.Metric,
) {
	log.Info("TCP eBPF collector started")
	defer log.Info("TCP eBPF collector stopped")

	for {
		select {
		case <-ctx.Done():
			t.loader.Close()
			return
		default:
		}

		events, err := t.loader.PollEvents(t.pollTimeout)
		if err != nil {
			log.Warnf("TCP ring buffer poll error: %v", err)
			continue
		}

		for _, ev := range events {
			metric := t.toProto(deviceID, ev)
			select {
			case out <- metric:
			default:
				log.Debugf("metric channel full — dropping TCP event pid=%d", ev.PID)
			}
		}
	}
}

func (t *TcpCollector) toProto(deviceID string, ev ebpf.TcpEvent) *pb.Metric {
	return &pb.Metric{
		DeviceId:  deviceID,
		Timestamp: time.Now().UnixMilli(),
		Status:    pb.MetricStatus_STATUS_OK,
		Payload: &pb.Metric_Tcp{
			Tcp: &pb.TcpMetric{
				Pid:      ev.PID,
				SrcAddr:  uint32ToIPString(ev.SrcAddr),
				DstAddr:  uint32ToIPString(ev.DstAddr),
				SrcPort:  uint32(ev.SrcPort),
				DstPort:  uint32(ev.DstPort),
				OldState: stateName(ev.OldState),
				NewState: stateName(ev.NewState),
				Comm:     ev.Comm,
			},
		},
	}
}

func uint32ToIPString(addr uint32) string {
	b := make([]byte, 4)
	binary.BigEndian.PutUint32(b, addr)
	return net.IP(b).String()
}
