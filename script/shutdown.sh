#!/usr/bin/env bash
# Stops the running servers.
#   ./shutdown.sh            frontend, then backend
#   ./shutdown.sh backend
#   ./shutdown.sh frontend
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

validate_operator

SERVICES="$(services_of "${1:-all}")"
FAILED=0

# The reverse of the start order: the frontend goes first.
for service in $(echo "$SERVICES" | tr ' ' '\n' | tac); do
  stop_service "$service" || FAILED=1
done

exit "$FAILED"
