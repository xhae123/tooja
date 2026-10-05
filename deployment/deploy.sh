#!/usr/bin/env bash
set -Eeuo pipefail
export TOOJA_HOME=/home/ubuntu/services/tooja
export RELEASE_DIR="$(cd "$(dirname "$0")" && pwd)"
export RELEASE_ID="$(basename "$RELEASE_DIR")"
exec 9>"$TOOJA_HOME/deploy.lock"
flock -w 300 9
previous=$(readlink -f "$TOOJA_HOME/current" || true)
compose() { docker compose -p tooja -f "$RELEASE_DIR/compose.yaml" "$@"; }
rollback() {
  trap - ERR
  printf 'Deployment failed; restoring the previous application release.\n' >&2
  if [ -n "$previous" ] && [ -f "$previous/compose.yaml" ]; then
    export RELEASE_DIR="$previous" RELEASE_ID="$(basename "$previous")"
    compose up -d --wait --wait-timeout 120
  else
    compose down
  fi
  exit 1
}
trap rollback ERR
compose build --pull
if [ -f "$TOOJA_HOME/data/tooja.db" ]; then
  python3 - "$TOOJA_HOME" "$RELEASE_ID" <<'PY'
import sqlite3,sys
from pathlib import Path
root=Path(sys.argv[1]); dst=root/'backups'/f'before-{sys.argv[2]}.db'
with sqlite3.connect(f'file:{root}/data/tooja.db?mode=ro',uri=True) as source:
    with sqlite3.connect(dst) as target: source.backup(target)
dst.chmod(0o600)
print('Consistent SQLite backup recorded before application replacement')
PY
fi
compose up -d --wait --wait-timeout 120
# nginx is configured once by the operator, and resolves app through Docker DNS.
# Deployments do not modify, rebuild, restart or reload nginx.
# Loopback crosses nginx while overseas HTTP access stays blocked.
# This is checked on the SSH-authenticated host, not exempted for GitHub IPs.
origin=$(sed -n 's/^APP_ORIGIN=//p' "$TOOJA_HOME/runtime.env")
edge_url=http://127.0.0.1
edge_host=${origin#http://}
curl --fail --retry 5 --retry-delay 2 -H "Host: $edge_host" "$edge_url/actuator/health"
curl --fail -H "Host: $edge_host" "$edge_url/v3/api-docs" -o "$RELEASE_DIR/reports/openapi.json"
curl --fail -H "Host: $edge_host" "$edge_url/reports/allure/index.html" -o /dev/null
curl --fail -H "Host: $edge_host" "$edge_url/deployment.json" -o "$RELEASE_DIR/reports/verified-deployment.json"
python3 - "$RELEASE_DIR" <<'PYVERIFY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
expected=json.loads((root/'reports/deployment.json').read_text())
actual=json.loads((root/'reports/verified-deployment.json').read_text())
assert actual==expected, 'Served deployment metadata differs from release'
assert actual['tests']['e2e']==0 and actual['tests']['failed']==0
print('DEPLOYMENT_VERIFIED '+json.dumps(actual,separators=(',',':')))
PYVERIFY
ln -s "$RELEASE_DIR" "$TOOJA_HOME/current.next"
mv -Tf "$TOOJA_HOME/current.next" "$TOOJA_HOME/current"
trap - ERR
printf '\nRelease activated: %s\n' "$RELEASE_ID"
