#!/usr/bin/env python3
"""Refresh KR CIDRs from DB-IP Lite without changing the nginx image or app.
A failed download/validation keeps the existing list. Changed lists are tested
and nginx is gracefully reloaded; failures restore the preceding list.
"""
import argparse
import csv
import datetime
import gzip
import hashlib
import io
import ipaddress
import json
from pathlib import Path
import subprocess


def generate(compressed):
    networks = []
    rows = 0
    with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
        for start, end, country in csv.reader(io.TextIOWrapper(stream, encoding='utf-8')):
            rows += 1
            if country != 'KR':
                continue
            lo, hi = ipaddress.ip_address(start), ipaddress.ip_address(end)
            if lo.version != hi.version or int(lo) > int(hi):
                raise ValueError('Invalid country IP range')
            networks.extend(ipaddress.summarize_address_range(lo, hi))
    v4 = list(ipaddress.collapse_addresses(n for n in networks if n.version == 4))
    v6 = list(ipaddress.collapse_addresses(n for n in networks if n.version == 6))
    if rows < 100000 or len(v4) < 100 or not v6:
        raise ValueError('Unexpected or empty country dataset')
    for n in v4 + v6:
        if n.prefixlen == 0 or n.is_loopback or n.is_private or n.is_multicast:
            raise ValueError('Unexpected non-public country range')
    header = '# KR geolocation CIDRs derived from DB-IP Lite, CC BY 4.0\n# Source and attribution: https://db-ip.com/db/download/ip-to-country-lite\n'
    content = header + ''.join(f'{n} 1;\n' for n in v4 + v6)
    metadata = dict(rows=rows, ipv4Cidrs=len(v4), ipv6Cidrs=len(v6), sourceSha256=hashlib.sha256(compressed).hexdigest())
    return content, metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, help='Previously downloaded gzip CSV')
    parser.add_argument('--destination', type=Path, default=Path('/home/ubuntu/edge/conf.d/tooja-kr-cidrs.inc'))
    parser.add_argument('--initial', action='store_true', help='Generate only, before initial operator nginx test/reload')
    args = parser.parse_args()
    month = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m')
    url = f'https://download.db-ip.com/free/dbip-country-lite-{month}.csv.gz'
    if args.source:
        compressed = args.source.read_bytes()
    else:
        # curl's maintained TLS/HTTP client is accepted by the download edge;
        # Python urllib's default client is rejected with HTTP 403 there.
        compressed = subprocess.check_output(['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2', '--max-time', '60', '--max-filesize', str(32 * 1024 * 1024), url])
        if len(compressed) > 32 * 1024 * 1024:
            raise ValueError('Dataset download too large')
    content, metadata = generate(compressed)
    metadata.update(source=url, checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    previous = args.destination.read_text() if args.destination.exists() else None
    if content == previous:
        print(json.dumps(dict(metadata, changed=False)))
        return
    staged = args.destination.with_suffix('.inc.next')
    staged.write_text(content)
    staged.replace(args.destination)
    try:
        if not args.initial:
            subprocess.run(['docker', 'exec', 'edge-nginx', 'nginx', '-t'], check=True)
            subprocess.run(['docker', 'exec', 'edge-nginx', 'nginx', '-s', 'reload'], check=True)
    except BaseException:
        if previous is not None:
            args.destination.write_text(previous)
        else:
            args.destination.unlink(missing_ok=True)
        raise
    args.destination.with_suffix('.metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(dict(metadata, changed=True)))


if __name__ == '__main__':
    main()
