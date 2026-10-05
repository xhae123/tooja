#!/usr/bin/env bash
# Operator-managed timer, independent from application CI/CD.
set -euo pipefail
certificate=/home/ubuntu/edge/certbot/conf/live/tooja-api/fullchain.pem
state=/home/ubuntu/edge/tooja-certificate.sha256
[ -f "$certificate" ] || exit 0
fingerprint=$(sha256sum "$certificate" | cut -d ' ' -f 1)
previous=$(cat "$state" 2>/dev/null || true)
if [ "$fingerprint" != "$previous" ]; then
  docker exec edge-nginx nginx -t
  docker exec edge-nginx nginx -s reload
  printf '%s\n' "$fingerprint" > "$state"
fi
