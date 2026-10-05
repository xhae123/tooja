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
# Dedicated cache namespace; never prune another service's build cache.
if ! docker buildx inspect tooja-release >/dev/null 2>&1; then
  docker buildx create --name tooja-release --driver docker-container --driver-opt default-load=true,memory=512m
fi
compose build --builder tooja-release --pull
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
# Resolve the public hostname to loopback, preserving HTTPS SNI and certificate verification.
read -r edge_host edge_port < <(python3 - "$origin" <<'PYORIGIN'
import sys
from urllib.parse import urlsplit
u=urlsplit(sys.argv[1])
assert u.scheme in ('http','https') and u.hostname and not u.username and not u.password
assert u.path in ('','/') and not u.query and not u.fragment
print(u.hostname, u.port or (443 if u.scheme=='https' else 80))
PYORIGIN
)
edge_curl() { curl --fail --resolve "$edge_host:$edge_port:127.0.0.1" "$@"; }
edge_curl --retry 5 --retry-delay 2 "$origin/actuator/health"
edge_curl "$origin/v3/api-docs" -o "$RELEASE_DIR/reports/openapi.json"
edge_curl "$origin/reports/allure/index.html" -o /dev/null
edge_curl "$origin/deployment.json" -o "$RELEASE_DIR/reports/verified-deployment.json"
python3 - "$RELEASE_DIR" <<'PYVERIFY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
expected=json.loads((root/'reports/deployment.json').read_text())
actual=json.loads((root/'reports/verified-deployment.json').read_text())
assert actual==expected, 'Served deployment metadata differs from release'
assert actual['tests']['e2e']==0 and actual['tests']['failed']==0
# Health JSON has no trailing newline; start evidence on its own line.
print('\nDEPLOYMENT_VERIFIED '+json.dumps(actual,separators=(',',':')))
PYVERIFY
ln -s "$RELEASE_DIR" "$TOOJA_HOME/current.next"
mv -Tf "$TOOJA_HOME/current.next" "$TOOJA_HOME/current"
trap - ERR
printf '\nRelease activated: %s\n' "$RELEASE_ID"

# Cleanup starts only after the new app and nginx checks succeeded.
# No past application release is retained after a successful deployment.
python3 - "$TOOJA_HOME" "$RELEASE_ID" <<'PYCLEANUP'
import json,re,shutil,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]).resolve()
release_id=sys.argv[2]
assert re.fullmatch(r'[a-f0-9]{40}\.[1-9][0-9]*\.[1-9][0-9]*',release_id)
current=(root/'current').resolve(strict=True)
assert current==root/'releases'/release_id
container=json.loads(subprocess.check_output(['docker','inspect','tooja-app'],text=True))[0]
assert container['Config']['Image']=='tooja:'+release_id
assert container['State']['Health']['Status']=='healthy'
refs=subprocess.check_output(['docker','image','ls','--filter','reference=tooja:*','--format','{{.Repository}}:{{.Tag}}'],text=True).splitlines()
removed_images=[]
for ref in sorted(set(refs)):
    assert ref.startswith('tooja:')
    if ref=='tooja:'+release_id: continue
    # No force: Docker refuses removal of an image used by any container.
    subprocess.run(['docker','image','rm',ref],check=True)
    removed_images.append(ref)
removed_releases=[]
for path in sorted((root/'releases').iterdir()):
    if path==current: continue
    if not re.fullmatch(r'[a-f0-9]{40}\.[1-9][0-9]*\.[1-9][0-9]*',path.name):
        raise RuntimeError('Unexpected release path; refusing deletion: '+path.name)
    if path.is_symlink() or not path.is_dir() or not (path/'app.jar').is_file():
        raise RuntimeError('Unexpected release contents; refusing deletion: '+path.name)
    shutil.rmtree(path)
    removed_releases.append(path.name)
print('RELEASE_CLEANUP '+json.dumps(dict(images=removed_images,releases=removed_releases)))
PYCLEANUP
docker buildx prune --builder tooja-release --all --force
docker buildx stop tooja-release
