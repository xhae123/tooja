#!/usr/bin/env bash
# Installed once by the operator. SSH key is restricted to this command.
set -Eeuo pipefail
root=/home/ubuntu/services/tooja
archive=$(mktemp "$root/incoming/release.XXXXXX.tar.gz")
trap 'rm -f "$archive"' EXIT
cat > "$archive"
release=$(python3 - "$archive" "$root" <<'PY'
import hashlib,json,re,sys,tarfile
from pathlib import Path
archive,root=sys.argv[1],Path(sys.argv[2])
allowed={'app.jar','Dockerfile','compose.yaml','deploy.sh','reports/allure/index.html','reports/deployment.json'}
with tarfile.open(archive,'r:gz') as bundle:
    entries=bundle.getmembers()
    if len(entries)!=len(allowed) or {m.name for m in entries}!=allowed or any(not m.isfile() for m in entries):
        raise SystemExit('Invalid release package: unexpected files, symlinks or duplicates')
    metadata=json.load(bundle.extractfile('reports/deployment.json'))
    if not re.fullmatch('[a-f0-9]{40}',metadata['commit']): raise SystemExit('Invalid commit')
    if not isinstance(metadata['runNumber'],int) or metadata['runNumber']<1: raise SystemExit('Invalid run number')
    if not isinstance(metadata['runAttempt'],int) or metadata['runAttempt']<1: raise SystemExit('Invalid run attempt')
    if metadata['tests']['failed'] or metadata['tests']['e2e'] or metadata['tests']['jvm']<1: raise SystemExit('Invalid test evidence')
    if hashlib.sha256(bundle.extractfile('app.jar').read()).hexdigest()!=metadata['jarSha256']: raise SystemExit('JAR checksum mismatch')
    destination=root/'releases'/f"{metadata['commit']}.{metadata['runNumber']}.{metadata['runAttempt']}"
    destination.mkdir(mode=0o755,exist_ok=False)
    for member in entries:
        output=destination/member.name;output.parent.mkdir(parents=True,exist_ok=True)
        output.write_bytes(bundle.extractfile(member).read());output.chmod(0o644)
    print(destination)
PY
)
bash "$release/deploy.sh"
