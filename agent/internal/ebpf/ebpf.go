// Package ebpf provides the CGo bridge to the libbpf-based TCP event loader.
//
// This file is the exact point where Go calls C. Everything above this layer
// (tcp_collector.go, collector.go) works in pure Go. Everything below
// (loader.c, loader.h, tcp_events.c) is C.
//
// Why C was necessary here (academic justification):
//   libbpf is a C library. Its API (bpf_object__open_file, bpf_program__attach,
//   ring_buffer__new, etc.) has no stable Go bindings that do not themselves
//   use CGo internally. The alternative — github.com/cilium/ebpf — is a
//   pure-Go eBPF library that reimplements the BPF syscall layer in Go.
//   For this project, the explicit Go → C progression is a design goal:
//   we use libbpf directly via CGo to demonstrate that C is the correct
//   tool when kernel-level interfaces require it, and Go is correct for
//   everything above the kernel boundary.
//
// CGo boundary design:
//   - The C API (loader.h) exposes no libbpf types. The Go side never
//     sees struct bpf_object or struct ring_buffer.
//   - Memory allocation: loader_open allocates the handle in C (malloc),
//     loader_close frees it. The Go side holds an unsafe.Pointer — it
//     never allocates or frees C memory directly.
//   - The event batch (s_batch in loader.c) is C memory. Go reads from
//     it via loader_get_event, copying each event into a Go struct before
//     the C pointer becomes invalid on the next loader_poll_events call.

//go:build linux

package ebpf

/*
#cgo CFLAGS:  -I. -I../..
#cgo LDFLAGS: -lbpf -lelf -lz

#include "loader.h"
#include <stdlib.h>
#include <stdint.h>
*/
import "C"
import (
	"fmt"
	"time"
	"unsafe"
)

type TcpEvent struct {
	PID      uint32
	SrcAddr  uint32
	DstAddr  uint32
	SrcPort  uint16
	DstPort  uint16
	OldState uint8
	NewState uint8
	Comm     string
}

type Loader struct {
	handle unsafe.Pointer
}

func Open(bpfObjectPath string) (*Loader, error) {
	path := C.CString(bpfObjectPath)
	defer C.free(unsafe.Pointer(path))

	h := C.loader_open(path)
	if h == nil {
		errStr := C.GoString(C.loader_last_error())
		return nil, fmt.Errorf("loader_open(%s): %s", bpfObjectPath, errStr)
	}

	return &Loader{handle: unsafe.Pointer(h)}, nil
}

func (l *Loader) PollEvents(timeout time.Duration) ([]TcpEvent, error) {
	h := (*C.struct_loader_handle)(l.handle)
	timeoutMs := C.int(timeout.Milliseconds())

	count := C.loader_poll_events(h, timeoutMs)
	if count < 0 {
		return nil, fmt.Errorf("loader_poll_events: ring buffer error")
	}

	if count == 0 {
		return nil, nil
	}

	events := make([]TcpEvent, int(count))
	for i := 0; i < int(count); i++ {
		ce := C.loader_get_event(C.int(i))
		if ce == nil {
			break
		}
		events[i] = TcpEvent{
			PID:      uint32(ce.pid),
			SrcAddr:  uint32(ce.src_addr),
			DstAddr:  uint32(ce.dst_addr),
			SrcPort:  uint16(ce.src_port),
			DstPort:  uint16(ce.dst_port),
			OldState: uint8(ce.old_state),
			NewState: uint8(ce.new_state),
			Comm:     C.GoString(&ce.comm[0]),
		}
	}

	return events, nil
}

func (l *Loader) Close() {
	if l.handle == nil {
		return
	}
	C.loader_close((*C.struct_loader_handle)(l.handle))
	l.handle = nil
}
