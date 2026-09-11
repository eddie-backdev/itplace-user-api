"""Pool completed-request means/TPS; keep trial percentiles separate (not additive)."""
import argparse
import json
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('results', type=Path, nargs='+')
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
trials = [json.loads(path.read_text()) for path in a.results]
for key in ('corpus_sha256', 'concurrency', 'accept_encoding', 'request_timeout_seconds'):
    assert len({t[key] for t in trials}) == 1, f'Mismatched {key}'
for t in trials:
    m = t['measurement']
    assert m['completed'] == m['issued'] == m['distinct_request_paths_issued']
    assert m['full_corpus_cycles_issued'] == 0
    assert sum(g['completed'] for g in m['groups'].values()) == m['completed']

def pool(rows, seconds):
    count = sum(r['completed'] for r in rows)
    errors = sum(r['errors'] for r in rows)
    return dict(completed=count, errors=errors, successful_tps=(count-errors)/sum(seconds),
                mean_ms=sum(r['mean_ms']*r['completed'] for r in rows)/count,
                p95_trial_range_ms=[min(r['p95_ms'] for r in rows), max(r['p95_ms'] for r in rows)],
                p99_trial_range_ms=[min(r['p99_ms'] for r in rows), max(r['p99_ms'] for r in rows)],
                mean_body_bytes=sum(r['body_bytes_completed'] for r in rows)/count)

measurements = [t['measurement'] for t in trials]
seconds = [m['wall_seconds_including_drain'] for m in measurements]
result = pool(measurements, seconds)
result['groups'] = {g: pool([m['groups'][g] for m in measurements], seconds) for g in measurements[0]['groups']}
result['trials'] = [dict(file=path.name, seed=t['seed'], completed=t['measurement']['completed'],
                         successful_tps=t['measurement']['successful_tps_including_drain'],
                         mean_ms=t['measurement']['mean_ms'], p95_ms=t['measurement']['p95_ms'],
                         p99_ms=t['measurement']['p99_ms'], errors=t['measurement']['errors'])
                    for path, t in zip(a.results, trials)]
result['conditions'] = {key: trials[0][key] for key in ('corpus_sha256', 'concurrency', 'accept_encoding', 'request_timeout_seconds')}
result['interpretation'] = 'Pooled count / sum(wall including drain); request-weighted mean. Percentiles remain trial ranges. No request path reuse within each measurement; paths may recur across separate trials.'
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
print(a.output)
