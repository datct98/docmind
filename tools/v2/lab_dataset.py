"""Development lab compilation. Explicit inputs only; never searches for owner holdout.

Workbook labels supervise BEHAVIOR. Old assistant text is retained for contrast,
never exported as a positive answer or sent in the runtime prompt.
"""
import argparse
import hashlib
import json
from pathlib import Path

VERSION = 'docmind-lab-v2.1'
RUBRIC = ['goal_progress', 'question_utility', 'naturalness', 'warmth',
          'persuasive_relevance', 'expected_experience_match', 'relationship',
          'closing_judgment', 'concision', 'response_rhythm', 'repair_effectiveness']
HARD_GATES = ['factual_scope', 'medical_expectation', 'transaction_receipt',
              'tenant_isolation', 'consent_and_stop', 'no_internal_leak']


def jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def compile_lab(package, coverage_path):
    workbook = package / 'assets/owner-reviewed/DocMind_Duyet_Hoi_Thoai_Danh_Cho_Ban-da-duyet.xlsx'
    digest = hashlib.sha256(workbook.read_bytes()).hexdigest()
    coverage = json.loads(coverage_path.read_text(encoding='utf-8'))
    labels = {r['reviewId']: r for r in jsonl(package / 'assets/owner-reviewed/owner_review_labels.jsonl')}
    old = {r['id']: r for name in ['episodes.jsonl', 'contrastive_pairs.jsonl']
           for r in jsonl(package / 'assets/lab-dev' / name)}
    if len(coverage) != 30 or set(labels) != {r['scenario_id'] for r in coverage} or set(labels) != set(old):
        raise ValueError('Owner cases are missing, duplicated or added without provenance')
    result = []
    for c in coverage:
        if c['workbook_sha256'] != digest:
            raise ValueError('Workbook drift: rerun the workbook reconciliation first')
        key = c['scenario_id']; label = labels[key]; original = old[key]
        if label['status'] != c['ownerDecision'] or label['status'] not in {'GIU', 'SUA'}:
            raise ValueError('Owner label disagreement')
        utterances = [m['text'] for m in original.get('episode', []) if m['role'] == 'user']
        if not utterances:
            utterances = [original['customer']]
        # These are requests/assertions, never literal assistant targets. STOP supersedes useful-answer repair.
        behaviors = list(c['expectedBehavior'])
        if 'STOP_NO_MORE_MESSAGES' in behaviors:
            behaviors = [b for b in behaviors if b != 'APOLOGY_PLUS_USEFUL_ANSWER']
        result.append(dict(scenario_id=key, datasetVersion=VERSION, split='development',
            customerGoal=label['customerGoalOrContext'], knownFacts={}, openLoops=[label['customerGoalOrContext']],
            customerUtterances=utterances, industry=original['industry'],
            businessFixtureRef='NANG_SEN_SOURCE_2026_11_07' if original['industry'] == 'BEAUTY_SPA' else 'ISOLATED_SYNTHETIC_' + original['industry'],
            ownerDecision=label['status'], ownerRationale=label['ownerFeedback'],
            desiredBehavior=behaviors, forbiddenBehavior=original.get('avoidBehavior', original.get('shouldNotInfer', [])),
            strategyEligible='DERIVE_FROM_CUSTOMER_EVIDENCE', strategyExcluded=['ANY_WHEN_STOP', 'CLOSE_WITHOUT_CUSTOMER_SIGNAL'],
            groundingPolicy='BUSINESS_FIXTURE_ONLY; owner examples are never factual authority',
            evaluationRubric=dict(scale=[1, 5], soft=RUBRIC, hard=HARD_GATES),
            sourceProvenance=dict(kind='SYNTHETIC_OWNER_REVIEWED', workbook_sha256=digest,
                                  sheet=c['sheet'], row=c['row']),
            originalAssistantTextDisposition='NEGATIVE_REWRITE_REQUIRED' if label['status'] == 'SUA' else 'BEHAVIOR_ONLY_NOT_LITERAL',
            original=original, positiveAnswer=None, correctedCandidate=None,
            correctionStatus='NEEDS_OBSERVED_REPLAY_AND_HUMAN_REVIEW', execution='NOT_RUN'))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, default=Path('artifacts/v2-contract'))
    parser.add_argument('--coverage', type=Path, default=Path('docs/evidence/v2/pr0/owner-coverage.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cases = compile_lab(args.package, args.coverage)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in cases), encoding='utf-8')
    print(json.dumps(dict(cases=len(cases), version=VERSION, positive_literal_answers=0,
                         human_acceptance='NOT_RUN', holdout_accessed=False)))


if __name__ == '__main__':
    main()
