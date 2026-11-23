#!/usr/bin/env bash
# Copies GoGo (shared with the Android app) into the iPhone web app. Run after editing GoGo.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf docs/gogo && cp -r app/src/main/assets/gogo docs/gogo
echo "Synced GoGo into docs/gogo"
