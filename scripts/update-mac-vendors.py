#!/usr/bin/env python3
"""Refresh offline MAC vendors from Wireshark's published IEEE-derived directory."""
import gzip
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

SOURCE = 'https://www.wireshark.org/download/automated/data/manuf.gz'

def main():
    with urlopen(SOURCE, timeout=90) as response:
        raw = response.read()
        modified = response.headers.get('Last-Modified')
    entries, notices = {}, []
    for line in gzip.decompress(raw).decode('utf-8').splitlines():
        if line.startswith('#'):
            notices.append(line)
            continue
        fields = line.split('#', 1)[0].split(None, 2)
        if len(fields) < 2:
            continue
        address, _, bits = fields[0].partition('/')
        bits = int(bits) if bits else len(address.replace(':', '')) * 4
        if bits not in (24, 28, 36):
            continue
        prefix = address.replace(':', '').upper()[:bits // 4]
        if len(prefix) != bits // 4 or any(c not in '0123456789ABCDEF' for c in prefix):
            raise ValueError(f'Unexpected prefix: {fields[0]}')
        entries[prefix] = ' '.join((fields[2] if len(fields) == 3 else fields[1]).split())
    if len(entries) < 30000:
        raise ValueError('Incomplete vendor directory')
    output = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'mac-vendors.tsv').write_text(''.join(f'{p}\t{n}\n' for p, n in sorted(entries.items())), encoding='utf-8')
    (output / 'mac-vendors-sources.json').write_text(json.dumps({'source': SOURCE, 'entries': len(entries),
        'last_modified': modified, 'sha256': hashlib.sha256(raw).hexdigest(),
        'upstream': 'https://gitlab.com/wireshark/wireshark/-/blob/master/tools/make-manuf.py',
        'license': 'GPL-2.0-or-later; distributed here under the project GPL-3.0 license'}, indent=2) + '\n', encoding='utf-8')
    (output / 'mac-vendors-NOTICE.txt').write_text('\n'.join(notices) + '\n', encoding='utf-8')
    print(f'Bundled {len(entries)} MAC-prefix assignments')

if __name__ == '__main__':
    main()
