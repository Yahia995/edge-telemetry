package config

import (
    "flag"
    "time"
)

type Config struct {
    DeviceID       string
    SamplingInterval time.Duration
    OutputFile     string
}

func LoadConfig() *Config {
    cfg := &Config{}

    flag.StringVar(&cfg.DeviceID, "device-id", "unknown", "Unique device identifier")
    flag.DurationVar(&cfg.SamplingInterval, "interval", 5*time.Second, "Metric sampling interval")
    flag.StringVar(&cfg.OutputFile, "output", "metrics.json", "Output file for metrics")
    
    flag.Parse()

    return cfg
}
