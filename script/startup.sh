#!/usr/bin/env bash
# Starts what deploy.sh last built.
#   ./startup.sh            backend, then frontend
#   ./startup.sh backend
#   ./startup.sh frontend
# A service that is already running is left alone.
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

validate_operator

SERVICES="$(services_of "${1:-all}")"

for service in $SERVICES; do
  start_service "$service"
done
