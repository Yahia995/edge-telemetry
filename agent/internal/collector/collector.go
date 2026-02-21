package collector

import (
	"context"
	"time"

	pb "github.com/Yahia995/edge-telemetry/agent/proto/telemetry"
	"github.com/Yahia995/edge-telemetry/agent/internal/config"
	log "github.com/sirupsen/logrus"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
)

const (
	initialBackoff = 2 * time.Second
	maxBackoff     = 30 * time.Second
)

type Collector struct {
	cfg          *config.Config
	cpuCollector *CpuCollector
	memCollector *MemoryCollector
	netCollector *NetworkCollector
	metricChan   chan *pb.Metric
}

func NewCollector(cfg *config.Config) (*Collector, error) {
	return &Collector{
		cfg:          cfg,
		cpuCollector: NewCpuCollector(),
		memCollector: NewMemoryCollector(),
		netCollector: NewNetworkCollector(),
		metricChan:   make(chan *pb.Metric, 100),
	}, nil
}

func (c *Collector) Start(ctx context.Context) {
	ticker := time.NewTicker(c.cfg.SamplingInterval)
	defer ticker.Stop()

	go c.grpcSender(ctx)

	log.Infof("Starting metric collection (interval: %v, backend: %s)",
		c.cfg.SamplingInterval, c.cfg.BackendAddr)

	for {
		select {
		case <-ctx.Done():
			log.Info("Shutting down collector")
			close(c.metricChan)
			return
		case <-ticker.C:
			c.collectAll()
		}
	}
}

func (c *Collector) collectAll() {
	timestamp := time.Now().UnixMilli()

	// CPU
	cpuMetric, cpuStatus, err := c.cpuCollector.Collect()
	if err != nil {
		log.Warnf("CPU collection error: %v", err)
	}
	c.send(&pb.Metric{
		DeviceId:  c.cfg.DeviceID,
		Timestamp: timestamp,
		Status:    cpuStatus,
		Payload:   &pb.Metric_Cpu{Cpu: cpuMetric},
	})

	// Memory
	memMetric, memStatus, err := c.memCollector.Collect()
	if err != nil {
		log.Warnf("Memory collection error: %v", err)
	}
	c.send(&pb.Metric{
		DeviceId:  c.cfg.DeviceID,
		Timestamp: timestamp,
		Status:    memStatus,
		Payload:   &pb.Metric_Memory{Memory: memMetric},
	})

	// Network (one message per interface)
	netMetrics, netStatus, err := c.netCollector.Collect()
	if err != nil {
		log.Warnf("Network collection error: %v", err)
	}
	for _, netMetric := range netMetrics {
		c.send(&pb.Metric{
			DeviceId:  c.cfg.DeviceID,
			Timestamp: timestamp,
			Status:    netStatus,
			Payload:   &pb.Metric_Network{Network: netMetric},
		})
	}
}

func (c *Collector) send(metric *pb.Metric) {
	select {
	case c.metricChan <- metric:
	default:
		log.Warn("Metric channel full — dropping metric (backend unavailable?)")
	}
}

// === gRPC transport ===
func (c *Collector) grpcSender(ctx context.Context) {
	backoff := initialBackoff

	for {
		if ctx.Err() != nil {
			return
		}

		err := c.runStream(ctx)

		if ctx.Err() != nil {
			return
		}

		log.Warnf("Stream to backend lost: %v — reconnecting in %v", err, backoff)

	drain:
		for {
			select {
			case <-c.metricChan:
			default:
				break drain
			}
		}

		select {
		case <-time.After(backoff):
		case <-ctx.Done():
			return
		}

		if backoff < maxBackoff {
			backoff *= 2
			if backoff > maxBackoff {
				backoff = maxBackoff
			}
		}
	}
}

func (c *Collector) runStream(ctx context.Context) error {
	conn, err := grpc.NewClient(
		c.cfg.BackendAddr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Time:                10 * time.Second,
			Timeout:             5 * time.Second,
			PermitWithoutStream: true,
		}),
	)
	if err != nil {
		return err
	}
	defer conn.Close()

	client := pb.NewTelemetryServiceClient(conn)

	stream, err := client.StreamMetrics(ctx)
	if err != nil {
		return err
	}

	log.Infof("Connected to backend at %s", c.cfg.BackendAddr)

	for {
		select {
		case <-ctx.Done():
			_, closeErr := stream.CloseAndRecv()
			if closeErr != nil {
				log.Debugf("Stream close: %v", closeErr)
			}
			return nil

		case metric, ok := <-c.metricChan:
			if !ok {
				stream.CloseAndRecv()
				return nil
			}
			if err := stream.Send(metric); err != nil {
				return err
			}
		}
	}
}

func (c *Collector) Close() error {
	return nil
}
