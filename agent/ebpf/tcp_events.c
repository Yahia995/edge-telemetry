// SPDX-License-Identifier: GPL-2.0
/*
 * tcp_events.c — eBPF TCP connection tracepoint
 *
 * Attaches to the kernel tracepoint: tracepoint/sock/inet_sock_set_state
 * This tracepoint fires on every TCP state transition. It is:
 *   - Stable since kernel 4.16 (available on all modern kernels)
 *   - Read-only (no kernel memory modification, no safety risk)
 *   - Low overhead: fires only on state changes, not on every packet
 *
 * The program filters for:
 *   - IPv4 only (family == AF_INET)
 *   - Transitions TO a meaningful state (ESTABLISHED, CLOSE_WAIT,
 *     FIN_WAIT1, TIME_WAIT, CLOSED) — ignoring transient states
 *     reduces ring buffer pressure.
 *
 * Each matching event is written to the tcp_events ring buffer map.
 * The userspace loader (loader.c) drains this buffer periodically.
 *
 * Compilation:
 *   clang -O2 -g -target bpf -D__TARGET_ARCH_x86_64 \
 *     -I/usr/include/bpf \
 *     -I/usr/include \
 *     -c ebpf/tcp_events.c -o ebpf/tcp_events.o
 *
 * Tracepoint format (from /sys/kernel/tracing/events/sock/inet_sock_set_state/format):
 *   offset 0:   common fields (8 bytes)
 *   offset 8:   skaddr   (8 bytes, pointer)
 *   offset 16:  oldstate (4 bytes, int)
 *   offset 20:  newstate (4 bytes, int)
 *   offset 24:  sport    (2 bytes, __u16)
 *   offset 26:  dport    (2 bytes, __u16)
 *   offset 28:  family   (2 bytes, __u16)
 *   offset 30:  protocol (1 byte,  __u8)
 *   offset 31:  saddr    (4 bytes, __u8[4])
 *   offset 35:  daddr    (4 bytes, __u8[4])
 */

#include <linux/bpf.h>
#include <linux/types.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#include "tcp_events.h"

#define TCP_ESTABLISHED  1
#define TCP_SYN_SENT     2
#define TCP_SYN_RECV     3
#define TCP_FIN_WAIT1    4
#define TCP_FIN_WAIT2    5
#define TCP_TIME_WAIT    6
#define TCP_CLOSE        7
#define TCP_CLOSE_WAIT   8
#define TCP_LAST_ACK     9
#define TCP_LISTEN       10
#define TCP_CLOSING      11

#define AF_INET  2

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 256 * 1024);
} tcp_events SEC(".maps");

struct inet_sock_set_state_ctx {
    __u64  _common;
    void  *skaddr;
    int    oldstate;
    int    newstate;
    __u16  sport;
    __u16  dport;
    __u16  family;
    __u8   protocol;
    __u8   saddr[4];
    __u8   daddr[4];
} __attribute__((packed));

static __always_inline int is_interesting_state(int new_state)
{
    return new_state == TCP_ESTABLISHED ||
           new_state == TCP_CLOSE_WAIT  ||
           new_state == TCP_FIN_WAIT1   ||
           new_state == TCP_TIME_WAIT   ||
           new_state == TCP_CLOSE;
}

SEC("tracepoint/sock/inet_sock_set_state")
int trace_inet_sock_set_state(struct inet_sock_set_state_ctx *ctx)
{
    __u16 family;
    int   new_state;

    bpf_probe_read_kernel(&family,    sizeof(family),    &ctx->family);
    bpf_probe_read_kernel(&new_state, sizeof(new_state), &ctx->newstate);

    if (family != AF_INET || !is_interesting_state(new_state))
        return 0;

    struct tcp_event *e = bpf_ringbuf_reserve(&tcp_events,
                                               sizeof(struct tcp_event), 0);
    if (!e)
        return 0;

    e->pid = bpf_get_current_pid_tgid() >> 32;

    __u8 saddr[4], daddr[4];
    bpf_probe_read_kernel(saddr, sizeof(saddr), ctx->saddr);
    bpf_probe_read_kernel(daddr, sizeof(daddr), ctx->daddr);
    e->src_addr = *(__u32 *)saddr;
    e->dst_addr = *(__u32 *)daddr;

    bpf_probe_read_kernel(&e->src_port, sizeof(e->src_port), &ctx->sport);
    bpf_probe_read_kernel(&e->dst_port, sizeof(e->dst_port), &ctx->dport);

    int old_state;
    bpf_probe_read_kernel(&old_state, sizeof(old_state), &ctx->oldstate);
    e->old_state = (__u8)old_state;
    e->new_state = (__u8)new_state;

    e->_pad[0] = 0;
    e->_pad[1] = 0;

    bpf_get_current_comm(e->comm, sizeof(e->comm));

    bpf_ringbuf_submit(e, 0);
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
