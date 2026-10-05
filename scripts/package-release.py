"""Package the successful run's JAR and report; never use tracked historical E2E HTML."""
import hashlib
import json
import os
import tarfile
import xml.etree.ElementTree as ET
from pathlib import Path

roots = [ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
if not roots:
    raise SystemExit('Missing JVM test results')
total = sum(int(r.get('tests', 0)) for r in roots)
failed = sum(int(r.get('failures', 0)) + int(r.get('errors', 0)) + int(r.get('skipped', 0)) for r in roots)
if failed:
    raise SystemExit('Release requires all JVM tests to pass without skips')
jar = Path('build/libs/tooja.jar')
metadata = {
    'commit': os.environ['GITHUB_SHA'],
    'runNumber': int(os.environ['GITHUB_RUN_NUMBER']),
    'runAttempt': int(os.environ.get('GITHUB_RUN_ATTEMPT', '1')),
    'runUrl': f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'tests': {'jvm': total, 'e2e': 0, 'failed': failed},
}
Path('build/deployment.json').write_text(json.dumps(metadata, indent=2)+'\n')
files = {
    'app.jar': jar,
    'Dockerfile': Path('deployment/Dockerfile'),
    'compose.yaml': Path('deployment/compose.yaml'),
    'deploy.sh': Path('deployment/deploy.sh'),
    'reports/allure/index.html': Path('reports/ci-allure/index.html'),
    'reports/deployment.json': Path('build/deployment.json'),
}
with tarfile.open('build/release.tar.gz', 'w:gz') as archive:
    for name, source in files.items():
        archive.add(source, arcname=name)
print(f'Packaged {total} passing JVM tests; no E2E results; commit {metadata["commit"]}')
