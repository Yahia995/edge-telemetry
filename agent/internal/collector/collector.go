package collector

import (
    "context"
    "encoding/json"
    "fmt"
    "os"
    "time"

    pb "github.com/Yahia995/edge-telemetry/agent/proto/telemetry"
    "github.com/Yahia995/edge-telemetry/agent/internal/config"
    log "github.com/sirupsen/logrus"
)

type Collector struct {
    cfg           *config.Config
    cpuCollector  *CpuCollector
    memCollector  *MemoryCollector
    netCollector  *NetworkCollector
    metricChan    chan *pb.Metric
    outputFile    *os.File
}

func NewCollector(cfg *config.Config) (*Collector, error) {
    // Open output file
    file, err := os.Create(cfg.OutputFile)
    if err != nil {
        return nil, fmt.Errorf("failed to create output file: %w", err)
    }

    return &Collector{
        cfg:          cfg,
        cpuCollector: NewCpuCollector(),
        memCollector: NewMemoryCollector(),
        netCollector: NewNetworkCollector(),
        metricChan:   make(chan *pb.Metric, 100), // Buffered channel
        outputFile:   file,
    }, nil
}

// Start begins the collection loop
func (c *Collector) Start(ctx context.Context) {
    ticker := time.NewTicker(c.cfg.SamplingInterval)
    defer ticker.Stop()

    // Start sender goroutine
    go c.sender(ctx)

    log.Infof("Starting metric collection (interval: %v)", c.cfg.SamplingInterval)

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

    // Collect CPU
    cpuMetric, cpuStatus, err := c.cpuCollector.Collect()
    if err != nil {
        log.Warnf("CPU collection error: %v", err)
    }
    c.metricChan <- &pb.Metric{
        DeviceId:  c.cfg.DeviceID,
        Timestamp: timestamp,
        Status:    cpuStatus,
        Payload:   &pb.Metric_Cpu{Cpu: cpuMetric},
    }

    // Collect Memory
    memMetric, memStatus, err := c.memCollector.Collect()
    if err != nil {
        log.Warnf("Memory collection error: %v", err)
    }
    c.metricChan <- &pb.Metric{
        DeviceId:  c.cfg.DeviceID,
        Timestamp: timestamp,
        Status:    memStatus,
        Payload:   &pb.Metric_Memory{Memory: memMetric},
    }

    // Collect Network (multiple interfaces)
    netMetrics, netStatus, err := c.netCollector.Collect()
    if err != nil {
        log.Warnf("Network collection error: %v", err)
    }
    for _, netMetric := range netMetrics {
        c.metricChan <- &pb.Metric{
            DeviceId:  c.cfg.DeviceID,
            Timestamp: timestamp,
            Status:    netStatus,
            Payload:   &pb.Metric_Network{Network: netMetric},
        }
    }
}

// sender writes metrics to output file (JSON for now)
func (c *Collector) sender(ctx context.Context) {
    encoder := json.NewEncoder(c.outputFile)
    encoder.SetIndent("", "  ")

    for {
        select {
        case <-ctx.Done():
            return
        case metric, ok := <-c.metricChan:
            if !ok {
                // Channel closed
                return
            }

            // Convert protobuf to JSON for human readability
            if err := encoder.Encode(metric); err != nil {
                log.Errorf("Failed to write metric: %v", err)
            }
        }
    }
}

func (c *Collector) Close() error {
    return c.outputFile.Close()
}
