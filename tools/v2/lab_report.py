"""Observed-replay metrics and blinded human-review export; never invent quality scores."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from lab_dataset import RUBRIC, HARD_GATES


def load_turns(path):
    if path.suffix == '.jsonl':
        rows = [json.loads(s) for s in path.read_text(encoding='utf-8').splitlines() if s.strip()]
        return [dict(scenario=r.get('scenario', 'unlabelled'), customer=r['customer'],
                     texts=[r['response'].get('reply_text', '')], elapsed=r['elapsed_ms'],
                     calls=None, fallback=None, violations=r['response'].get('triggered_hooks', [])) for r in rows]
    rows = json.loads(path.read_text(encoding='utf-8'))['turns']
    return [dict(scenario=r.get('scenario', 'unlabelled'), customer=r['customer'],
                 texts=r['result']['texts'], elapsed=r['result']['elapsedMs'], calls=r['result']['attempts'],
                 fallback=r['result']['fallback'], violations=r['result']['violations']) for r in rows]


def metrics(turns):
    latencies = sorted(r['elapsed'] for r in turns)
    def percentile(p):
        return None if not latencies else latencies[max(0, math.ceil(len(latencies) * p) - 1)]
    return dict(turns=len(turns), scenarios=len({r['scenario'] for r in turns}),
                p50_ms=percentile(.50), p95_ms=percentile(.95),
                fallback_count=sum(r['fallback'] is True for r in turns),
                fallback_observations_available=all(r['fallback'] is not None for r in turns),
                mean_calls=sum(r['calls'] for r in turns)/len(turns) if turns and all(r['calls'] is not None for r in turns) else None,
                hard_gate_status='REQUIRES_SOURCE_AND_HUMAN_REVIEW', human_score=None)


def longest_shared_ngram(left, right, n=8):
    # A flag for review only. Common greetings/facts alone cannot prove copying.
    a, b = left.casefold().split(), right.casefold().split()
    if len(a) < n or len(b) < n:
        return False
    grams = {tuple(a[i:i+n]) for i in range(len(a)-n+1)}
    return any(tuple(b[i:i+n]) in grams for i in range(len(b)-n+1))


def export(after, baseline, out):
    out.mkdir(parents=True, exist_ok=True)
    groups = {}
    for arm, rows in [('baseline', baseline), ('candidate', after)]:
        for row in rows:
            groups.setdefault(row['scenario'], {}).setdefault(arm, []).append(row)
    key = {}; review = []
    for scenario, arms in groups.items():
        ordered = sorted(arms, key=lambda arm: hashlib.sha256((scenario+arm).encode()).hexdigest())
        for i, arm in enumerate(ordered):
            label = chr(65+i); ident = scenario + '-' + label
            key[ident] = arm
            transcript = '\n\n'.join('KH: '+r['customer']+'\nBOT: '+'\n'.join(r['texts']) for r in arms[arm])
            review.append(dict(case=ident, paired='YES' if len(arms)==2 else 'NO', transcript=transcript,
                               **{k:'' for k in RUBRIC+HARD_GATES}, rationale='', reviewer=''))
    with (out/'human-review.csv').open('w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['case','paired','transcript']+RUBRIC+HARD_GATES+['rationale','reviewer'])
        w.writeheader(); w.writerows(review)
    (out/'review-key.json').write_text(json.dumps(key, indent=2), encoding='utf-8')
    (out/'metrics.json').write_text(json.dumps(dict(baseline=metrics(baseline), candidate=metrics(after),
        human_acceptance='NOT_RUN', conversion_claim='NOT_MEASURED'), indent=2), encoding='utf-8')


def main():
    p = argparse.ArgumentParser(); p.add_argument('--after',type=Path,required=True)
    p.add_argument('--baseline',type=Path); p.add_argument('--output',type=Path,required=True)
    a = p.parse_args(); after=load_turns(a.after); baseline=load_turns(a.baseline) if a.baseline else []
    export(after,baseline,a.output); print(json.dumps(metrics(after)))


if __name__ == '__main__':
    main()
