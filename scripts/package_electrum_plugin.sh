#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
MANIFEST_PATH="$ROOT_DIR/nostr_signer/manifest.json"
VERSION=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' "$MANIFEST_PATH")
ARTIFACT_DIR="$ROOT_DIR/dist"
ARTIFACT_PATH="$ARTIFACT_DIR/nostr_signer-v${VERSION}.zip"

mkdir -p "$ARTIFACT_DIR"
rm -f "$ARTIFACT_PATH"

(
  cd "$ROOT_DIR"
  zip -X -qr "$ARTIFACT_PATH" nostr_signer
)

printf '%s\n' "$ARTIFACT_PATH"
