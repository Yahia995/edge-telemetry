-- =============================================================================
-- Edge Telemetry — PostgreSQL Schema
-- Phase 3: Persistent metric storage
--
-- Design notes:
--   - Three typed metric tables (cpu / memory / network) rather than a single
--     JSONB table. Our schema is fixed at compile time; typed columns give us
--     index efficiency and query planner visibility.
--   - TIMESTAMPTZ throughout. PostgreSQL stores as UTC internally; this makes
--     timezone-aware range queries correct by default. It also keeps the schema
--     TimescaleDB-compatible for Phase 5 (create_hypertable requires a native
--     timestamp column, not a BIGINT unix epoch).
--   - BIGSERIAL surrogate PKs. Timestamps can collide when multiple network
--     interfaces arrive in the same millisecond; surrogate keys avoid this.
--   - All metric tables have a composite index on (device_id, ts DESC).
--     Every query the backend issues is scoped to a device and ordered by time.
--     Without this index, range queries would be full table scans.
--   - devices table holds mutable summary state (last cpu %, last seen, etc.)
--     separately from the append-only metric tables. This avoids recomputing
--     the latest snapshot on every GET /api/devices call.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Devices
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS devices (
    id              TEXT PRIMARY KEY,
    name            TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'offline'
                                CHECK (status IN ('online', 'offline')),
    last_seen       TIMESTAMPTZ,
    cpu_percent     DOUBLE PRECISION,
    memory_percent  DOUBLE PRECISION,
    network_rx_mbps DOUBLE PRECISION,
    platform        TEXT        NOT NULL DEFAULT 'Linux',
    cpu_cores       INT         NOT NULL DEFAULT 0,
    uptime_seconds  BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  devices                IS 'One row per known edge device. Mutable summary state updated on each ingest cycle.';
COMMENT ON COLUMN devices.id             IS 'Matches --device-id flag on the agent.';
COMMENT ON COLUMN devices.status         IS 'online = seen within last 30s; offline = stale or never connected.';
COMMENT ON COLUMN devices.cpu_percent    IS 'Last observed CPU usage %. Denormalised from metric_cpu for fast list queries.';
COMMENT ON COLUMN devices.memory_percent IS 'Last observed memory usage %. Denormalised from metric_memory.';
COMMENT ON COLUMN devices.network_rx_mbps IS 'Last observed RX rate in MB/s across all interfaces.';
COMMENT ON COLUMN devices.cpu_cores     IS 'Populated when agent sends a system-info message (Phase 4+). 0 until then.';
COMMENT ON COLUMN devices.uptime_seconds IS 'Populated when agent sends a system-info message (Phase 4+). 0 until then.';

-- ---------------------------------------------------------------------------
-- CPU metrics
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS metric_cpu (
    id            BIGSERIAL        PRIMARY KEY,
    device_id     TEXT             NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ts            TIMESTAMPTZ      NOT NULL,
    usage_percent DOUBLE PRECISION NOT NULL,
    load_avg_1m   DOUBLE PRECISION NOT NULL,
    load_avg_5m   DOUBLE PRECISION NOT NULL,
    load_avg_15m  DOUBLE PRECISION NOT NULL
);

COMMENT ON TABLE  metric_cpu              IS 'Append-only CPU samples. One row per agent collection cycle (default 5 s).';
COMMENT ON COLUMN metric_cpu.ts           IS 'Collection timestamp in UTC. Matches the Unix ms timestamp from the Protobuf message.';
COMMENT ON COLUMN metric_cpu.usage_percent IS 'Delta-calculated usage from /proc/stat jiffies. 0.0 on first sample.';

-- Index on (device_id, ts DESC) — all history queries are device-scoped and time-ordered.
-- The DESC order matches ORDER BY ts DESC queries directly without a sort step.
CREATE INDEX IF NOT EXISTS idx_cpu_device_ts
    ON metric_cpu (device_id, ts DESC);

-- ---------------------------------------------------------------------------
-- Memory metrics
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS metric_memory (
    id               BIGSERIAL        PRIMARY KEY,
    device_id        TEXT             NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ts               TIMESTAMPTZ      NOT NULL,
    total_kb         BIGINT           NOT NULL,
    available_kb     BIGINT           NOT NULL,
    used_kb          BIGINT           NOT NULL,
    usage_percent    DOUBLE PRECISION NOT NULL,
    swap_total_kb    BIGINT           NOT NULL DEFAULT 0,
    swap_free_kb     BIGINT           NOT NULL DEFAULT 0,
    swap_usage_pct   DOUBLE PRECISION NOT NULL DEFAULT 0.0
);

COMMENT ON TABLE  metric_memory           IS 'Append-only memory samples from /proc/meminfo.';
COMMENT ON COLUMN metric_memory.available_kb IS 'MemAvailable, not MemFree. Includes reclaimable page cache.';
COMMENT ON COLUMN metric_memory.used_kb   IS 'Derived: total_kb - available_kb. Stored for query convenience.';

CREATE INDEX IF NOT EXISTS idx_memory_device_ts
    ON metric_memory (device_id, ts DESC);

-- ---------------------------------------------------------------------------
-- Network metrics
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS metric_network (
    id               BIGSERIAL   PRIMARY KEY,
    device_id        TEXT        NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ts               TIMESTAMPTZ NOT NULL,
    interface_name   TEXT        NOT NULL,
    rx_bytes_per_sec BIGINT      NOT NULL DEFAULT 0,
    tx_bytes_per_sec BIGINT      NOT NULL DEFAULT 0,
    rx_errors        BIGINT      NOT NULL DEFAULT 0,
    tx_errors        BIGINT      NOT NULL DEFAULT 0,
    rx_dropped       BIGINT      NOT NULL DEFAULT 0,
    tx_dropped       BIGINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE  metric_network               IS 'Append-only network samples from /proc/net/dev. One row per interface per cycle.';
COMMENT ON COLUMN metric_network.interface_name IS 'e.g. wlp4s0, eno1, lo. All interfaces reported; caller filters.';
COMMENT ON COLUMN metric_network.rx_bytes_per_sec IS 'Delta bytes / delta seconds calculated by the agent. 0 on first sample.';

-- Composite index includes interface_name so queries filtered to a single
-- interface (e.g. "show me wlp4s0 history") can use the index fully.
CREATE INDEX IF NOT EXISTS idx_network_device_ts
    ON metric_network (device_id, ts DESC);

CREATE INDEX IF NOT EXISTS idx_network_device_iface_ts
    ON metric_network (device_id, interface_name, ts DESC);

-- ---------------------------------------------------------------------------
-- Helper view: latest snapshot per device
-- ---------------------------------------------------------------------------
-- Used by GET /api/devices/:id/metrics/latest.
-- A view avoids duplicating the "get the most recent row per device" logic
-- across multiple repository queries.

CREATE OR REPLACE VIEW v_latest_cpu AS
SELECT DISTINCT ON (device_id)
    device_id, ts, usage_percent, load_avg_1m, load_avg_5m, load_avg_15m
FROM metric_cpu
ORDER BY device_id, ts DESC;

CREATE OR REPLACE VIEW v_latest_memory AS
SELECT DISTINCT ON (device_id)
    device_id, ts,
    total_kb, available_kb, used_kb, usage_percent,
    swap_total_kb, swap_free_kb, swap_usage_pct
FROM metric_memory
ORDER BY device_id, ts DESC;

CREATE OR REPLACE VIEW v_latest_network AS
SELECT DISTINCT ON (device_id, interface_name)
    device_id, interface_name, ts,
    rx_bytes_per_sec, tx_bytes_per_sec,
    rx_errors, tx_errors, rx_dropped, tx_dropped
FROM metric_network
ORDER BY device_id, interface_name, ts DESC;

-- ---------------------------------------------------------------------------
-- Notes for Phase 5 (TimescaleDB migration)
-- ---------------------------------------------------------------------------
-- When Phase 5 introduces TimescaleDB, the migration is minimal:
--
--   SELECT create_hypertable('metric_cpu',     'ts', if_not_exists => TRUE);
--   SELECT create_hypertable('metric_memory',  'ts', if_not_exists => TRUE);
--   SELECT create_hypertable('metric_network', 'ts', if_not_exists => TRUE);
--
-- The existing indexes on (device_id, ts DESC) are kept as-is; TimescaleDB
-- builds chunk-level indexes automatically on top of them.
-- No application code changes are required.
-- ---------------------------------------------------------------------------
