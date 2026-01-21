package collector

import (
    "bufio"
    "fmt"
    "os"
    "strconv"
    "strings"

    pb "github.com/Yahia995/edge-telemetry/agent/proto/telemetry"
)

type MemoryCollector struct{}

func NewMemoryCollector() *MemoryCollector {
    return &MemoryCollector{}
}

func (m *MemoryCollector) Collect() (*pb.MemoryMetric, pb.MetricStatus, error) {
    file, err := os.Open("/proc/meminfo")
    if err != nil {
        return nil, pb.MetricStatus_STATUS_PERMISSION_DENIED, fmt.Errorf("failed to open /proc/meminfo: %w", err)
    }
    defer file.Close()

    // Parse key-value pairs
    memInfo := make(map[string]uint64)
    scanner := bufio.NewScanner(file)
    
    for scanner.Scan() {
        line := scanner.Text()
        fields := strings.Fields(line)
        
        if len(fields) < 2 {
            continue
        }

        // Remove trailing colon from key
        key := strings.TrimSuffix(fields[0], ":")
        
        // Parse value (in kB)
        value, err := strconv.ParseUint(fields[1], 10, 64)
        if err != nil {
            continue // Skip malformed lines
        }

        memInfo[key] = value
    }

    if err := scanner.Err(); err != nil {
        return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("error reading /proc/meminfo: %w", err)
    }

    // Validate required fields exist
    required := []string{"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}
    for _, field := range required {
        if _, ok := memInfo[field]; !ok {
            return nil, pb.MetricStatus_STATUS_PARSE_ERROR, fmt.Errorf("missing required field: %s", field)
        }
    }

    // Calculate derived values
    memTotal := memInfo["MemTotal"]
    memAvailable := memInfo["MemAvailable"]
    memUsed := memTotal - memAvailable
    memUsagePercent := 100.0 * float32(memUsed) / float32(memTotal)

    swapTotal := memInfo["SwapTotal"]
    swapFree := memInfo["SwapFree"]
    var swapUsagePercent float32
    if swapTotal > 0 {
        swapUsed := swapTotal - swapFree
        swapUsagePercent = 100.0 * float32(swapUsed) / float32(swapTotal)
    } else {
        swapUsagePercent = 0.0
    }

    return &pb.MemoryMetric{
        TotalKb:           memTotal,
        AvailableKb:       memAvailable,
        UsedKb:            memUsed,
        UsagePercent:      memUsagePercent,
        SwapTotalKb:       swapTotal,
        SwapFreeKb:        swapFree,
        SwapUsagePercent:  swapUsagePercent,
    }, pb.MetricStatus_STATUS_OK, nil
}
