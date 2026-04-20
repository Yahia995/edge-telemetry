/* =============================================================================
 * loader.h — libbpf userspace loader API
 *
 * This header is the only C surface imported by the CGo bridge (ebpf.go).
 * No libbpf types appear here — the CGo boundary is kept narrow so that
 * changes to libbpf internals do not require Go code changes.
 *
 * Lifecycle:
 *   1. loader_open(path)      — open + load + attach the BPF program
 *   2. loader_poll_events(h)  — drain the ring buffer into a static batch
 *   3. loader_get_event(i)    — read one event from the batch by index
 *   4. loader_close(h)        — detach, close, free all resources
 *
 * Thread-safety:
 *   Not thread-safe. The Go caller (tcp_collector.go) calls these
 *   functions from a single goroutine. Do not call concurrently.
 * ============================================================================= */

#pragma once

#include <stdint.h>
#include "../../ebpf/tcp_events.h"

struct loader_handle;

#define LOADER_MAX_BATCH 64

struct loader_handle *loader_open(const char *bpf_obj_path);

int loader_poll_events(struct loader_handle *h, int timeout_ms);

const struct tcp_event *loader_get_event(int index);

void loader_close(struct loader_handle *h);

const char *loader_last_error(void);
