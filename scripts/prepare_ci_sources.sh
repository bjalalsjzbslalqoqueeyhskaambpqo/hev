#!/usr/bin/env bash
set -euo pipefail

BASE="app/src/main/java/com/blacktunnel"

if [[ -f "$BASE/btvpn.src" ]]; then
  mv "$BASE/btvpn.src" "$BASE/BtVpnService.java"
fi

if [[ -f "$BASE/smode.src" ]]; then
  mv "$BASE/smode.src" "$BASE/SimpleModeActivity.java"
fi
