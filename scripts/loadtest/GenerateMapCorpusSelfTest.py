"""Check map-movement scope and explicit historical corpus modes without API traffic."""
import collections
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

generator = Path(__file__).with_name('generate-map-corpus.py')
with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    anchors = root / 'anchors.json'
    anchors.write_text(json.dumps([{'id': 1, 'lat': 37.5, 'lng': 127.0}]))

    def generate(name, *flags):
        output = root / f'{name}.tsv'
        subprocess.run([sys.executable, str(generator), '--anchors', str(anchors),
                        '--output', str(output), '--count', '80', *flags],
                       check=True, stdout=subprocess.DEVNULL)
        rows = [line.split('\t') for line in output.read_text().splitlines()]
        return rows, json.loads(output.with_suffix('.json').read_text())

    rows, meta = generate('movement')
    assert meta['scope'] == 'map-movement-only'
    assert meta['groups'] == {'preview': 50, 'cluster5': 10, 'cluster7': 10, 'cluster10': 10}
    assert meta['distinct_centers'] == meta['distinct_paths'] == 80
    for group, path in rows:
        url = urlsplit(path)
        query = parse_qs(url.query)
        assert 'keyword' not in query and 'radiusMeters' not in query
        if group == 'preview':
            assert url.path == '/api/v1/maps/stores/in-view/previews/compact'
            assert query['limit'] == ['300']
        else:
            assert url.path == '/api/v1/maps/stores/in-view/clusters'
            assert query['mapLevel'] == [group.removeprefix('cluster')]
    repeat, _ = generate('repeat')
    assert rows == repeat

    mixed, mixed_meta = generate('mixed', '--historical-mixed')
    legacy, _ = generate('legacy', '--historical-mixed', '--legacy-previews')
    assert mixed_meta['scope'] == 'historical-web-mixed'
    assert mixed_meta['groups']['keyword'] == mixed_meta['groups']['nearby'] == 8
    for (group, path), (old_group, old_path) in zip(mixed, legacy):
        assert group == old_group
        assert path == (old_path.replace('/previews?', '/previews/compact?')
                        if group in ('keyword', 'nearby') else old_path)
    mobile, mobile_meta = generate('mobile', '--include-mobile')
    assert mobile_meta['scope'] == 'historical-mobile-inclusive'
    assert collections.Counter(group for group, _ in mobile)['mobile'] == 4

print('Passed: default map-only URLs/levels, unique seeded coordinates, historical modes.')
