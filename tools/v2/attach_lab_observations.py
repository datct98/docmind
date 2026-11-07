"""Attach observed attempts, never self-promote them as owner-approved positive targets."""
import argparse
import hashlib
import json
from pathlib import Path
from lab_dataset import compile_lab
from lab_report import load_turns, longest_shared_ngram


def attach(cases, turns, replay_sha):
    by_id={}
    for turn in turns:
        by_id.setdefault(turn['scenario'],[]).append(turn)
    for case in cases:
        actual=by_id.get(case['scenario_id'],[])
        if not actual:
            continue
        original=case['original']
        reference=' '.join(m['text'] for m in original.get('episode',[]) if m['role']=='assistant')
        reference+=' '+str(original.get('better',''))
        case['execution']='OBSERVED_REPLAY'
        case['correctedCandidate']={
            'status':'CANDIDATE_REQUIRES_FACT_AND_HUMAN_REVIEW',
            'sourceProvenance':'OBSERVED_REPLAY', 'replay_sha256':replay_sha,
            'turns':actual,'behaviorChangeRequested':case['desiredBehavior'],
            'observedFallbacks':sum(r['fallback'] is True for r in actual),
            'lexicalCopyFlag8Words':any(longest_shared_ngram(' '.join(r['texts']), reference) for r in actual),
            'semanticSimilarity':'NOT_MEASURED', 'humanScore':None}
        case['correctionStatus']='OBSERVED_CANDIDATE_NOT_ACCEPTED'
        # Even a zero-fallback candidate may be dry, inaccurate, intrusive or unhelpful.
        case['positiveAnswer']=None
    return cases


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--replay',type=Path,required=True);p.add_argument('--alternate',type=Path,action='append',default=[]);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    cases=compile_lab(Path('artifacts/v2-contract'),Path('docs/evidence/v2/pr0/owner-coverage.json'))
    result=attach(cases,load_turns(a.replay),hashlib.sha256(a.replay.read_bytes()).hexdigest())
    for alternate in a.alternate:
        attempts=attach(compile_lab(Path('artifacts/v2-contract'),Path('docs/evidence/v2/pr0/owner-coverage.json')),
                        load_turns(alternate),hashlib.sha256(alternate.read_bytes()).hexdigest())
        by_id={r['scenario_id']:r for r in attempts if r['correctedCandidate'] is not None}
        for case in result:
            if case['scenario_id'] in by_id:
                case.setdefault('alternativeCandidates',[]).append(by_id[case['scenario_id']]['correctedCandidate'])
    a.output.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in result),encoding='utf-8')
    print(json.dumps(dict(cases=len(result),executed=sum(r['execution']=='OBSERVED_REPLAY' for r in result),human_accepted=0)))
