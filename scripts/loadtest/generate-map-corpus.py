"""Generate seeded, unique web map requests from public store coordinate anchors (JSON)."""
import argparse
import collections
import hashlib
import json
import random
from pathlib import Path
from urllib.parse import urlencode

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--anchors', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--count', type=int, default=200000)
p.add_argument('--seed', type=int, default=20260911)
p.add_argument('--include-mobile', action='store_true', help='Reproduce the historical mobile-inclusive corpus; excluded by default.')
args = p.parse_args()
assert args.count > 0
anchors = json.loads(args.anchors.read_text())
assert anchors and all(33 <= a['lat'] <= 39 and 124 <= a['lng'] <= 132 for a in anchors)
rng = random.Random(args.seed)
groups, centers, paths, selected = collections.Counter(), set(), set(), set()
categories = [None, None, None, None, '푸드', '생활/편의', '쇼핑', '문화/여가']
keywords = ['스타벅스', 'GS25', 'CU', '편의점', '카페', '존재하지않는지점xyz', '다', '이디야']
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('w') as out:
    for i in range(args.count):
        if i % 5:
            anchor = rng.choice(anchors)
            selected.add(anchor['id'])
            lat, lng = anchor['lat'] + rng.uniform(-.004, .004), anchor['lng'] + rng.uniform(-.004, .004)
        else:
            lat, lng = rng.uniform(33.1, 38.5), rng.uniform(126, 129.6)
        lat, lng = round(lat, 7), round(lng, 7)
        assert (lat, lng) not in centers
        centers.add((lat, lng))
        slot, category = i % 20, rng.choice(categories)
        if slot < 16:
            level = [5, 7, 10][(slot - 10) // 2] if slot >= 10 else None
            extent = {5: .035, 7: .15, 10: .65}.get(level, rng.choice([.003, .006, .012, .022]))
            q = dict(minLat=f'{lat-extent:.7f}', maxLat=f'{lat+extent:.7f}', minLng=f'{lng-extent*1.25:.7f}', maxLng=f'{lng+extent*1.25:.7f}')
            if level:
                group, endpoint = f'cluster{level}', '/api/v1/maps/stores/in-view/clusters'
                q['mapLevel'] = level
            else:
                group, endpoint = 'preview', '/api/v1/maps/stores/in-view/previews/compact'
                q['limit'] = 300
            if category:
                q['category'] = category
        else:
            q = dict(lat=f'{lat:.7f}', lng=f'{lng:.7f}', userLat=f'{lat:.7f}', userLng=f'{lng:.7f}')
            if slot < 18:
                group, endpoint = 'keyword', '/api/v1/maps/nearby/search/previews'
                q['keyword'] = rng.choice(keywords)
            else:
                mobile = args.include_mobile and slot == 19
                group = 'mobile' if mobile else 'nearby'
                endpoint = '/api/v1/mobile/map/nearby' if mobile else '/api/v1/maps/nearby/previews'
                q['radiusMeters'] = rng.choice([400, 800, 1500, 3000])
                if mobile:
                    q['carrier'] = rng.choice(['SKT', 'KT', 'LGU'])
        path = endpoint + '?' + urlencode(q)
        assert path not in paths
        paths.add(path)
        groups[group] += 1
        out.write(group + '\t' + path + '\n')
meta = dict(seed=args.seed, entries=args.count, groups=dict(groups), distinct_centers=len(centers), distinct_paths=len(paths), available_store_anchors=len(anchors), selected_store_anchors=len(selected), store_anchor_fraction=.8, uniform_korea_rectangle_fraction=.2, bounds=dict(minLat=min(x[0] for x in centers), maxLat=max(x[0] for x in centers), minLng=min(x[1] for x in centers), maxLng=max(x[1] for x in centers)), anchors_sha256=hashlib.sha256(args.anchors.read_bytes()).hexdigest(), corpus_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest(), warning='Synthetic mix, not observed production traffic. Uniform rectangle includes empty/sea locations. Each measurement must report actual issued unique paths and reject wraparound for diverse-request claims.')
meta['scope'] = 'historical-mobile-inclusive' if args.include_mobile else 'web-only'
args.output.with_suffix('.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(meta, ensure_ascii=False))
