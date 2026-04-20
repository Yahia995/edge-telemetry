/* =============================================================================
 * loader.c — libbpf userspace BPF loader
 *
 * Implements the API declared in loader.h.
 *
 * Dependencies (installed by Makefile prerequisite check):
 *   libbpf-devel  (provides bpf/libbpf.h, bpf/libbpf.a)
 *   elfutils-libelf-devel  (transitive libbpf dep for ELF parsing)
 *   zlib-devel             (transitive libbpf dep for compressed BTF)
 *
 * Compile:
 *   gcc -O2 -Wall -I/usr/include -c internal/ebpf/loader.c -o loader.o
 *   (handled by the CGo build via cgo CFLAGS / LDFLAGS in ebpf.go)
 * ============================================================================= */

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include <bpf/libbpf.h>

#include "loader.h"

struct loader_handle {
    struct bpf_object      *obj;  
    struct bpf_program     *prog;  
    struct bpf_link        *link; 
    struct ring_buffer      *rb; 
    struct bpf_map         *rb_map;
};

static struct tcp_event  s_batch[LOADER_MAX_BATCH];
static int               s_batch_count = 0;

static char s_last_error[256] = "no error";

static int on_tcp_event(void *ctx, void *data, size_t data_sz)
{
    (void)ctx;

    if (data_sz != sizeof(struct tcp_event)) {
        return 0;
    }

    if (s_batch_count >= LOADER_MAX_BATCH)
        return 0;  

    memcpy(&s_batch[s_batch_count++], data, sizeof(struct tcp_event));
    return 0;
}

struct loader_handle *loader_open(const char *bpf_obj_path)
{
    int err;

    struct loader_handle *h = calloc(1, sizeof(*h));
    if (!h) {
        snprintf(s_last_error, sizeof(s_last_error), "calloc failed: %s",
                 strerror(errno));
        return NULL;
    }

    h->obj = bpf_object__open_file(bpf_obj_path, NULL);
    if (!h->obj) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "bpf_object__open_file(%s) failed: %s",
                 bpf_obj_path, strerror(errno));
        goto err_free;
    }

    err = bpf_object__load(h->obj);
    if (err) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "bpf_object__load failed (err=%d): %s — "
                 "ensure CAP_BPF is granted (see compose.yaml cap_add)",
                 err, strerror(-err));
        goto err_close_obj;
    }

    h->prog = bpf_object__find_program_by_name(h->obj,
                  "trace_inet_sock_set_state");
    if (!h->prog) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "program 'trace_inet_sock_set_state' not found in %s",
                 bpf_obj_path);
        goto err_close_obj;
    }

    h->link = bpf_program__attach(h->prog);
    if (!h->link) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "bpf_program__attach failed: %s", strerror(errno));
        goto err_close_obj;
    }

    h->rb_map = bpf_object__find_map_by_name(h->obj, "tcp_events");
    if (!h->rb_map) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "ring buffer map 'tcp_events' not found in %s", bpf_obj_path);
        goto err_detach;
    }

    h->rb = ring_buffer__new(bpf_map__fd(h->rb_map), on_tcp_event, NULL, NULL);
    if (!h->rb) {
        snprintf(s_last_error, sizeof(s_last_error),
                 "ring_buffer__new failed: %s", strerror(errno));
        goto err_detach;
    }

    return h;

err_detach:
    bpf_link__destroy(h->link);
err_close_obj:
    bpf_object__close(h->obj);
err_free:
    free(h);
    return NULL;
}

int loader_poll_events(struct loader_handle *h, int timeout_ms)
{
    s_batch_count = 0;

    int ret = ring_buffer__poll(h->rb, timeout_ms);
    if (ret < 0 && ret != -EINTR)
        return -1;

    return s_batch_count;
}

const struct tcp_event *loader_get_event(int index)
{
    if (index < 0 || index >= s_batch_count)
        return NULL;
    return &s_batch[index];
}

void loader_close(struct loader_handle *h)
{
    if (!h)
        return;

    if (h->rb)
        ring_buffer__free(h->rb);

    if (h->link)
        bpf_link__destroy(h->link);

    if (h->obj)
        bpf_object__close(h->obj);

    free(h);
}

const char *loader_last_error(void)
{
    return s_last_error;
}
