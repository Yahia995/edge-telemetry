package main

import (
    "context"
    "os"
    "os/signal"
    "syscall"

    "github.com/Yahia995/edge-telemetry/agent/internal/collector"
    "github.com/Yahia995/edge-telemetry/agent/internal/config"
    log "github.com/sirupsen/logrus"
)

func main() {
    // Setup logging
    log.SetFormatter(&log.TextFormatter{
        FullTimestamp: true,
    })
    log.SetLevel(log.InfoLevel)

    // Load configuration
    cfg := config.LoadConfig()
    log.Infof("Starting agent for device: %s", cfg.DeviceID)

    // Create collector
    coll, err := collector.NewCollector(cfg)
    if err != nil {
        log.Fatalf("Failed to create collector: %v", err)
    }
    defer coll.Close()

    // Setup signal handling for graceful shutdown
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()

    sigChan := make(chan os.Signal, 1)
    signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

    go func() {
        sig := <-sigChan
        log.Infof("Received signal: %v, shutting down", sig)
        cancel()
    }()

    // Start collection
    coll.Start(ctx)

    log.Info("Agent stopped")
}
