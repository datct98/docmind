"""Build explicit owner-development replay inputs, with no original bot text or hidden holdout."""
import argparse
import json
from pathlib import Path
from lab_dataset import compile_lab


def cases(package, coverage, fixture_root):
    result = []
    # Pair-only short messages need a customer-authored preceding goal, not the criticized old bot answer.
    context = {
        'PAIR-002': ['Tôi muốn massage vai gáy, giá thế nào?'],
        'PAIR-003': ['Tôi muốn massage cơ bản tại Long Biên, cho tôi giá.'],
        'PAIR-004': ['Tôi ở Long Biên, muốn massage vai gáy, tiền không phải vấn đề.'],
        'PAIR-009': ['Tôi muốn thuê Vision hai ngày, nhận ở Hoàn Kiếm.'],
    }
    for case in compile_lab(package, coverage):
        industry = case['industry']
        kind = 'spa' if industry == 'BEAUTY_SPA' else 'rental' if industry == 'MOTORBIKE_RENTAL' else 'saas' if industry == 'SOFTWARE_SERVICE' else 'service'
        result.append(dict(id=case['scenario_id'], fixture=str(fixture_root/(kind+'.json')),
            messages=context.get(case['scenario_id'], [])+case['customerUtterances'],
            contextProvenance='SYNTHETIC_CUSTOMER_CONTEXT' if case['scenario_id'] in context else 'OWNER_REVIEWED_CUSTOMER_UTTERANCES',
            originalBotTextUsed=False, ownerDecision=case['ownerDecision']))
    return result


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    value=cases(Path('artifacts/v2-contract'),Path('docs/evidence/v2/pr0/owner-coverage.json'),Path('../artifacts/v2-pr4/fixtures'))
    a.output.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(cases=len(value),turns=sum(len(r['messages']) for r in value),bot_examples_in_prompt=0)))
