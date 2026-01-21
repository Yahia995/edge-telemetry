package collector

import (
    "bufio"
    "fmt"
    "os"
    "strconv"
    "strings"
    "time"

    pb "github.com/Yahia995/edge-telemetry/agent/proto/telemetry"
)

type NetworkCollector struct {
    prevStats map[string]*interfaceStats
    prevTime  time.Time
    firstSample bool
}

type interfaceStats struct {
    rxBytes uint64
    txBytes uint64
    rxErrs  uint64
    txErrs  uint64
    rxDrop  uint64
    txDrop  uint64
}

func NewNetworkCollector() *NetworkCollector {
    return &NetworkCollector{
        prevStats:   make(map[string]*interfaceStats),
        firstSample: true,
    }
}

func (n *NetworkCollector) Collect() ([]*pb.NetworkMetric, pb.MetricStatus, error) {
    file, err := os.Open("/proc/net/dev")
    if err != nil {
        return nil, pb.MetricStatus_STATUS_PERMISSION_DENIED, fmt.Errorf("failed to open /proc/net/dev: %w", err)
    }
    defer file.Close()

    scanner := bufio.NewScanner(file)
    
    // Skip header lines (2 lines)
    for i := 0; i < 2; i++ {
        if !scanner.Scan() {
            return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("unexpected /proc/net/dev format")
        }
    }

    now := time.Now()
    currentStats := make(map[string]*interfaceStats)

    // Parse interface lines
    for scanner.Scan() {
        line := scanner.Text()
        
        // Split by colon to separate interface name
        parts := strings.Split(line, ":")
        if len(parts) != 2 {
            continue // Skip malformed lines
        }

        ifaceName := strings.TrimSpace(parts[0])
        fields := strings.Fields(parts[1])

        if len(fields) < 16 {
            continue // Skip incomplete lines
        }

        // Parse values (see /proc/net/dev format)
        // Receive: bytes packets errs drop fifo frame compressed multicast
        // Transmit: bytes packets errs drop fifo colls carrier compressed
        rxBytes, _ := strconv.ParseUint(fields[0], 10, 64)
        rxErrs, _ := strconv.ParseUint(fields[2], 10, 64)
        rxDrop, _ := strconv.ParseUint(fields[3], 10, 64)
        
        txBytes, _ := strconv.ParseUint(fields[8], 10, 64)
        txErrs, _ := strconv.ParseUint(fields[10], 10, 64)
        txDrop, _ := strconv.ParseUint(fields[11], 10, 64)

        currentStats[ifaceName] = &interfaceStats{
            rxBytes: rxBytes,
            txBytes: txBytes,
            rxErrs:  rxErrs,
            txErrs:  txErrs,
            rxDrop:  rxDrop,
            txDrop:  txDrop,
        }
    }

    if err := scanner.Err(); err != nil {
        return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("error reading /proc/net/dev: %w", err)
    }

    var metrics []*pb.NetworkMetric

    // Calculate rates for each interface
    if n.firstSample {
        // First sample: no rates, just capture state
        n.firstSample = false
        n.prevStats = currentStats
        n.prevTime = now

        // Return zero-rate metrics for all interfaces
        for ifaceName, stats := range currentStats {
            metrics = append(metrics, &pb.NetworkMetric{
                InterfaceName:   ifaceName,
                RxBytesPerSec:   0,
                TxBytesPerSec:   0,
                RxErrors:        stats.rxErrs,
                TxErrors:        stats.txErrs,
                RxDropped:       stats.rxDrop,
                TxDropped:       stats.txDrop,
            })
        }
    } else {
        // Calculate rates
        timeDelta := now.Sub(n.prevTime).Seconds()
        
        if timeDelta == 0 {
            timeDelta = 1.0 // Prevent division by zero
        }

        for ifaceName, currentStat := range currentStats {
            prevStat, exists := n.prevStats[ifaceName]
            
            var rxRate, txRate uint64
            if exists {
                // Calculate byte rates
                deltaRx := currentStat.rxBytes - prevStat.rxBytes
                deltaTx := currentStat.txBytes - prevStat.txBytes
                
                rxRate = uint64(float64(deltaRx) / timeDelta)
                txRate = uint64(float64(deltaTx) / timeDelta)
            } else {
                // New interface appeared, use zero rate
                rxRate, txRate = 0, 0
            }

            metrics = append(metrics, &pb.NetworkMetric{
                InterfaceName:   ifaceName,
                RxBytesPerSec:   rxRate,
                TxBytesPerSec:   txRate,
                RxErrors:        currentStat.rxErrs,
                TxErrors:        currentStat.txErrs,
                RxDropped:       currentStat.rxDrop,
                TxDropped:       currentStat.txDrop,
            })
        }

        // Update state for next call
        n.prevStats = currentStats
        n.prevTime = now
    }

    return metrics, pb.MetricStatus_STATUS_OK, nil
}
