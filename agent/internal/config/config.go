package config

import (
	"flag"
	"time"
)

type Config struct {
	DeviceID         string
	SamplingInterval time.Duration
	BackendAddr      string
}

func LoadConfig() *Config {
	cfg := &Config{}

	flag.StringVar(&cfg.DeviceID, "device-id", "unknown", "Unique device identifier")
	flag.DurationVar(&cfg.SamplingInterval, "interval", 5*time.Second, "Metric sampling interval")
	flag.StringVar(&cfg.BackendAddr, "backend-addr", "localhost:50051", "Backend gRPC address (host:port)")

	flag.Parse()

	return cfg
}
