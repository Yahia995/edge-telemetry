#!/bin/bash
set -e

PROTO_DIR="proto"
OUT_DIR="proto/telemetry"

mkdir -p "$OUT_DIR"

protoc \
  --go_out="$OUT_DIR" \
  --go_opt=paths=source_relative \
  --go-grpc_out="$OUT_DIR" \
  --go-grpc_opt=paths=source_relative \
  -I="$PROTO_DIR" \
  "$PROTO_DIR/telemetry.proto"

echo "Protobuf code generated in $OUT_DIR"
