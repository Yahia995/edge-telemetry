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

type CpuCollector struct {
	prevTotal uint64
	prevIdle uint64
	prevTime time.Time
	firstSample bool
}

func NewCpuCollector() *CpuCollector {
	return &CpuCollector {
		firstSample: true,
	}
}

// Collect reads /proc/stat and calculates CPU usage percentage
func (c *CpuCollector) Collect() (*pb.CpuMetric, pb.MetricStatus, error) {
	// Read /proc/stat
	file, err := os.Open("/proc/stat")
	if err != nil {
		return nil, pb.MetricStatus_STATUS_PERMISSION_DENIED, fmt.Errorf("failed to open /proc/stat: %w", err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)

	// First line should be aggregate CPU stats
    if !scanner.Scan() {
        return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("empty /proc/stat")
    }

    line := scanner.Text()
    if !strings.HasPrefix(line, "cpu ") {
        return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("unexpected /proc/stat format")
    }

    // Parse CPU line
    fields := strings.Fields(line)
    if len(fields) < 5 {
        return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("insufficient fields in /proc/stat cpu line")
    }

    // Extract jiffies (skip "cpu" label)
    // cpu user nice system idle iowait irq softirq steal guest guest_nice
    var jiffies []uint64
    for i := 1; i < len(fields); i++ {
        val, err := strconv.ParseUint(fields[i], 10, 64)
        if err != nil {
            return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("failed to parse jiffy value: %w", err)
        }
        jiffies = append(jiffies, val)
    }

    // Calculate totals
    var total uint64
    for _, j := range jiffies {
        total += j
    }
    
    // idle = idle + iowait (fields[3] + fields[4])
    idle := jiffies[3]
    if len(jiffies) > 4 {
        idle += jiffies[4]
    }

    now := time.Now()

    // Calculate usage percentage
    var usagePercent float32
    if c.firstSample {
        // First sample: no previous data, return 0
        usagePercent = 0.0
        c.firstSample = false
    } else {
        deltaTotal := total - c.prevTotal
        deltaIdle := idle - c.prevIdle
        
        if deltaTotal == 0 {
            // Prevent division by zero (shouldn't happen with 5s interval)
            usagePercent = 0.0
        } else {
            usagePercent = 100.0 * (1.0 - float32(deltaIdle)/float32(deltaTotal))
        }
    }

    // Update state for next call
    c.prevTotal = total
    c.prevIdle = idle
    c.prevTime = now

    // Read load averages from /proc/loadavg
    loadAvg1, loadAvg5, loadAvg15, err := c.readLoadAvg()
    if err != nil {
        // Non-fatal: log but continue with zero values
        loadAvg1, loadAvg5, loadAvg15 = 0, 0, 0
    }

    return &pb.CpuMetric{
        UsagePercent: usagePercent,
        LoadAvg_1M:   loadAvg1,
        LoadAvg_5M:   loadAvg5,
        LoadAvg_15M:  loadAvg15,
    }, pb.MetricStatus_STATUS_OK, nil
}

func (c *CpuCollector) readLoadAvg() (float32, float32, float32, error) {
    data, err := os.ReadFile("/proc/loadavg")
    if err != nil {
        return 0, 0, 0, err
    }

    // Format: "0.52 0.58 0.59 1/422 12345"
    fields := strings.Fields(string(data))
    if len(fields) < 3 {
        return 0, 0, 0, fmt.Errorf("unexpected /proc/loadavg format")
    }

    load1, _ := strconv.ParseFloat(fields[0], 32)
    load5, _ := strconv.ParseFloat(fields[1], 32)
    load15, _ := strconv.ParseFloat(fields[2], 32)

    return float32(load1), float32(load5), float32(load15), nil
}
