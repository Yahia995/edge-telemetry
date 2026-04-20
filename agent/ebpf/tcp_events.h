/* =============================================================================
 * tcp_events.h — Shared TCP event definition
 *
 * Included by:
 *   ebpf/tcp_events.c      (compiled to BPF bytecode by clang)
 *   internal/ebpf/loader.c (userspace libbpf loader, read by CGo)
 *
 * The struct must be identical on both sides. Because the BPF program
 * writes instances into a ring buffer and the userspace loader reads them
 * out, any mismatch causes silent data corruption.
 *
 * Field sizing rationale:
 *   - __u32 for addresses: IPv4 only in Phase 6.
 *     IPv6 support (using __u8[16]) is a Phase 7 extension.
 *   - __u16 for ports: kernel port range is 0–65535, fits in uint16.
 *   - __u8  for states: TCP has 11 states (0–10), fits in uint8.
 *   - comm[16]: TASK_COMM_LEN is 16 in all Linux kernels.
 * ============================================================================= */

#pragma once

#include <linux/types.h>

#define TCP_COMM_LEN 16

struct tcp_event {
    __u32 pid;
    __u32 src_addr;
    __u32 dst_addr;
    __u16 src_port;
    __u16 dst_port;
    __u8  old_state; 
    __u8  new_state;
    __u8  _pad[2];
    char  comm[TCP_COMM_LEN];
};
