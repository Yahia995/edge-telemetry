package config

import (
	"flag"
	"os"
	"time"
)

type Config struct {
	DeviceID         string
	SamplingInterval time.Duration
	BackendAddr      string
}

func LoadConfig() *Config {
	cfg := &Config{}

	flag.StringVar(
		&cfg.DeviceID,
		"device-id",
		envOr("DEVICE_ID", "unknown"),
		"Unique device identifier (env: DEVICE_ID)",
	)

	flag.DurationVar(
		&cfg.SamplingInterval,
		"interval",
		envDurationOr("SAMPLING_INTERVAL", 5*time.Second),
		"Metric sampling interval (env: SAMPLING_INTERVAL, e.g. 5s)",
	)

	flag.StringVar(
		&cfg.BackendAddr,
		"backend-addr",
		envOr("BACKEND_ADDR", "localhost:50051"),
		"Backend gRPC address host:port (env: BACKEND_ADDR)",
	)

	flag.Parse()

	return cfg
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envDurationOr(key string, fallback time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return fallback
	}
	return d
}
