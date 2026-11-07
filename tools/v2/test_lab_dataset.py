import csv
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from lab_dataset import compile_lab
from lab_report import metrics, export, longest_shared_ngram
from attach_lab_observations import attach

ROOT = Path(__file__).resolve().parents[2]


class LabTests(unittest.TestCase):
    def test_every_owner_label_is_preserved_without_promoting_bad_examples(self):
        cases = compile_lab(ROOT/'artifacts/v2-contract', ROOT/'docs/evidence/v2/pr0/owner-coverage.json')
        self.assertEqual(30, len(cases))
        self.assertEqual(15, sum(r['ownerDecision']=='SUA' for r in cases))
        self.assertTrue(all(r['positiveAnswer'] is None for r in cases))
        self.assertTrue(all(r['originalAssistantTextDisposition']=='NEGATIVE_REWRITE_REQUIRED'
                            for r in cases if r['ownerDecision']=='SUA'))
        stop = next(r for r in cases if r['scenario_id']=='SPA-DEV-009')
        self.assertNotIn('APOLOGY_PLUS_USEFUL_ANSWER', stop['desiredBehavior'])

    def test_empty_or_successful_mock_run_cannot_be_reported_as_human_acceptance(self):
        result = metrics([dict(scenario='mock', elapsed=2, calls=2, fallback=False)])
        self.assertIsNone(result['human_score'])
        self.assertEqual('REQUIRES_SOURCE_AND_HUMAN_REVIEW', result['hard_gate_status'])
        self.assertIsNone(metrics([])['p95_ms'])

    def test_workbook_drift_refuses_compilation(self):
        coverage = ROOT/'docs/evidence/v2/pr0/owner-coverage.json'
        original = json.loads(coverage.read_text(encoding='utf-8'))
        original[0]['workbook_sha256'] = 'wrong'
        read_text = Path.read_text
        def changed(path, *args, **kwargs):
            return json.dumps(original) if path == coverage else read_text(path, *args, **kwargs)
        with patch.object(Path, 'read_text', changed):
            with self.assertRaisesRegex(ValueError, 'Workbook drift'):
                compile_lab(ROOT/'artifacts/v2-contract', coverage)

    def test_copy_flag_detects_reused_sentences_without_treating_greetings_as_proof(self):
        self.assertTrue(longest_shared_ngram('một hai ba bốn năm sáu bảy tám chín', 'một hai ba bốn năm sáu bảy tám khác'))
        self.assertFalse(longest_shared_ngram('Dạ em chào mình', 'Dạ em chào mình'))

    def test_successful_replay_does_not_promote_a_sua_case_without_human_review(self):
        cases=compile_lab(ROOT/'artifacts/v2-contract',ROOT/'docs/evidence/v2/pr0/owner-coverage.json')
        turns=[dict(scenario='SPA-DEV-001',customer='Giá',texts=['Câu trả lời quan sát'],elapsed=100,calls=2,fallback=False,violations=[])]
        result=attach(cases,turns,'recorded-hash')
        case=next(r for r in result if r['scenario_id']=='SPA-DEV-001')
        self.assertEqual('SUA',case['ownerDecision'])
        self.assertIsNone(case['positiveAnswer'])
        self.assertEqual('OBSERVED_CANDIDATE_NOT_ACCEPTED',case['correctionStatus'])
        self.assertEqual('CANDIDATE_REQUIRES_FACT_AND_HUMAN_REVIEW',case['correctedCandidate']['status'])
        self.assertTrue(all(r['execution']=='NOT_RUN' for r in result if r['scenario_id']!='SPA-DEV-001'))


if __name__ == '__main__':
    unittest.main()
