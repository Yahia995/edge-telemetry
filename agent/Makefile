# =============================================================================
# Edge Telemetry Agent — Makefile
#
# Targets:
#   make              — compile BPF object + Go binary (CGO_ENABLED=1)
#   make bpf          — compile BPF object only
#   make agent        — compile Go binary only (uses pre-built BPF object)
#   make clean        — remove build artefacts
#   make prereqs      — install build dependencies (Fedora/dnf5)
#
# Prerequisites (installed by `make prereqs` or manually):
#   clang             — BPF bytecode compiler (already installed: Clang 21.1.8)
#   libbpf-devel      — libbpf headers and static library
#   elfutils-libelf-devel — ELF parsing (transitive libbpf dep)
#   linux-headers     — kernel headers for BPF type definitions
#
# Note on CGO_ENABLED:
#   The Go binary uses CGo to call the C loader (internal/ebpf/loader.c).
#   CGO_ENABLED=1 (the default) is correct here. CGO_ENABLED=0 would produce
#   a static binary that cannot call C, breaking the eBPF integration.
#   When building for non-Linux targets or in environments without libbpf,
#   set ENABLE_EBPF=false at runtime; the binary still compiles because
#   tcp_collector.go carries the //go:build linux guard.
# =============================================================================

.PHONY: all bpf agent clean prereqs

BPF_SRC  := ebpf/tcp_events.c
BPF_OBJ  := ebpf/tcp_events.o
BPF_INC  := -I/usr/include/bpf -I/usr/include

CLANG    := clang
CC_FLAGS := -O2 -g -target bpf -D__TARGET_ARCH_x86_64 $(BPF_INC)

AGENT_BIN := bin/agent
AGENT_SRC := cmd/agent/main.go

GO       := go
GOFLAGS  :=   # CGO_ENABLED=1 is the default

all: bpf agent

bpf: $(BPF_OBJ)

$(BPF_OBJ): $(BPF_SRC) ebpf/tcp_events.h
	@echo "  CLANG  $<"
	@mkdir -p ebpf
	$(CLANG) $(CC_FLAGS) -c $< -o $@
	@echo "  BPF object: $@"

agent: $(BPF_OBJ)
	@echo "  GO     $(AGENT_SRC)"
	@mkdir -p bin
	$(GO) build $(GOFLAGS) -trimpath -ldflags="-s -w" -o $(AGENT_BIN) $(AGENT_SRC)
	@echo "  Binary: $(AGENT_BIN)"

clean:
	rm -f $(BPF_OBJ) $(AGENT_BIN)

prereqs:
	@echo "Installing build prerequisites..."
	sudo dnf5 install -y \
	  clang \
	  libbpf-devel \
	  elfutils-libelf-devel \
	  kernel-devel
	@echo "Prerequisites installed."
	@echo "Verify:"
	@echo "  clang --version"
	@echo "  rpm -q libbpf-devel"
