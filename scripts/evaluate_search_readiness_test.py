#!/usr/bin/env python3
"""Small stdlib regression checks for evaluator failure modes and readiness."""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('evaluate-search-readiness.py')
SPEC = importlib.util.spec_from_file_location('evaluator', SCRIPT)
E = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E)


def case(case_id='one', needs_review=False, label_status='verified'):
    return {'id': case_id, 'surface': 'chat', 'execution': 'api', 'request': {'question': 'CU'},
            'assertions': {'needs_review': needs_review, 'http_status': [200], 'carriers': ['KT'],
                           'required_brands': [], 'forbidden_brands': [], 'status': ['ANSWER']},
            'label_status': label_status}


def result(**values):
    return {'case_id': 'one', 'input': {'question': 'CU'}, 'http_status': 200, 'status': 'ANSWER', 'carriers': ['KT'], 'brands': [], **values}


class EvaluatorTest(unittest.TestCase):
    def test_missing_is_not_pass_and_strict_readiness_stays_false(self):
        out = E.evaluate({'cases': [case()]}, {'results': []})
        self.assertEqual(['one'], out['missing'])
        self.assertEqual([], out['evaluated'])
        self.assertFalse(out['complete'])
        self.assertFalse(out['ready'])

    def test_duplicate_ids_rejected(self):
        out = E.evaluate({'cases': [case(), case()]}, {'results': []})
        self.assertTrue(any('duplicate case id' in message for message in out['failures']))

    def test_invalid_corpus_shapes_do_not_crash_cli(self):
        for corpus in ([], {'cases': None}, {'cases': []}):
            with self.subTest(corpus=corpus), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'bad.json'
                path.write_text(json.dumps(corpus), encoding='utf-8')
                proc = subprocess.run([sys.executable, str(SCRIPT), '--cases', str(path), '--strict'],
                                      capture_output=True, text=True)
                self.assertEqual(1, proc.returncode)
                json.loads(proc.stdout)

    def test_malformed_result_and_assertions_rejected(self):
        out = E.evaluate({'cases': [case()]}, {'results': [{'case_id': 'one', 'carriers': 'KT'}]})
        self.assertTrue(any('carriers must be a string array' in message for message in out['malformed']))
        invalid = case(); invalid['assertions']['required_brands'] = 'CU'
        checked = E.evaluate({'cases': [invalid]}, {'results': []})
        self.assertTrue(any('required_brands must be a string array' in message for message in checked['failures']))

    def test_baseline_inputs_match_the_nine_corpus_requests(self):
        root = Path(__file__).resolve().parents[1]
        baseline = json.loads((root / 'docs/evaluation/chat-service-baseline-2026-10-20.json').read_text(encoding='utf-8'))
        corpus = json.loads((root / 'docs/evaluation/search-chat-cases.json').read_text(encoding='utf-8'))
        by_id = {item['id']: item for item in corpus['cases']}
        self.assertEqual(9, len(baseline['results']))
        for item in baseline['results']:
            with self.subTest(case_id=item['case_id']):
                self.assertEqual(item['input'], by_id[item['case_id']]['request'])
        evaluated = E.evaluate(corpus, baseline)
        self.assertEqual(15, evaluated['hard_checks'])
        self.assertEqual(['롯데시네마', '메가박스'], by_id['chat-movie-001']['assertions']['required_brands'])
        self.assertEqual(['할리스'], by_id['chat-cafe-001']['assertions']['required_brands'])
        self.assertEqual(['KT'], by_id['chat-carrier-kt-001']['assertions']['carriers'])
        self.assertEqual('VIP', by_id['chat-grade-lgu']['request']['grade'])

    def test_current_enum_and_api_surface_contracts(self):
        root = Path(__file__).resolve().parents[1]
        corpus = json.loads((root / 'docs/evaluation/search-chat-cases.json').read_text(encoding='utf-8'))
        self.assertEqual([], E.corpus_errors(corpus))
        by_id = {item['id']: item for item in corpus['cases']}
        self.assertEqual({'chat', 'benefit', 'map', 'recommend'}, {item['surface'] for item in corpus['cases']})
        self.assertEqual('GET /api/v1/questions/recommend', by_id['recommend-brand']['endpoint'])
        bad = json.loads(json.dumps(corpus)); bad['cases'][0]['request']['grade'] = 'LGU_VIP'
        self.assertTrue(any('current Grade enum' in error for error in E.corpus_errors(bad)))

    def test_result_input_mismatch_is_malformed(self):
        out = E.evaluate({'cases': [case()]}, {'results': [result(input={'question': 'wrong'})]})
        self.assertTrue(any('result.input does not match materialized corpus request' in message for message in out['malformed']))
        self.assertEqual([], out['evaluated'])

    def test_runtime_bound_candidate_key_requires_materialized_corpus(self):
        key = 'benefit:fixture:policy:fixture:tier:policy'
        c = case(); c['input_bindings'] = ['previousCandidateKeys']
        c['request']['previousCandidateKeys'] = []
        template = E.evaluate({'cases': [c]}, {'results': [result(input=dict(c['request']))]})
        self.assertTrue(any('not materialized' in message for message in template['malformed']))
        c['request']['previousCandidateKeys'] = [key]  # run-specific copy after fixture binding
        out = E.evaluate({'cases': [c]}, {'results': [result(input=dict(c['request']))]})
        self.assertEqual(['one'], out['evaluated'])
        wrong = dict(c['request']); wrong['previousCandidateKeys'] = []
        rejected = E.evaluate({'cases': [c]}, {'results': [result(input=wrong)]})
        self.assertFalse(rejected['ready'])
        self.assertTrue(any('materialized corpus request' in message for message in rejected['malformed']))

    def test_result_input_is_required(self):
        out = E.evaluate({'cases': [case()]}, {'results': [{'case_id': 'one'}]})
        self.assertTrue(any('input is required' in message for message in out['malformed']))
        self.assertFalse(out['ready'])

    def test_explicit_error_cannot_pass(self):
        out = E.evaluate({'cases': [case()]}, {'results': [result(error='retrieval timeout')]})
        self.assertFalse(out['ready'])
        self.assertTrue(any('reported error' in message for message in out['malformed']))

    def test_declared_checks_can_pass_without_being_release_readiness(self):
        out = E.evaluate({'cases': [case()]}, {'results': [result()]})
        self.assertTrue(out['ready'])  # evaluator contract only; release readiness is a separate decision
        self.assertTrue(out['complete'])

    def test_review_pending_means_strict_not_ready_even_when_all_returned(self):
        c = case(needs_review=True, label_status='verified')
        out = E.evaluate({'cases': [c]}, {'results': [result()]})
        self.assertEqual([], out['missing'])
        self.assertEqual(['one'], out['needs_review'])
        self.assertEqual(4, out['hard_checks'])
        self.assertTrue(out['complete'])
        self.assertFalse(out['ready'])

    def test_strict_cli_rejects_all_returned_but_pending_review(self):
        with tempfile.TemporaryDirectory() as tmp:
            cases_path, results_path = Path(tmp) / 'cases.json', Path(tmp) / 'results.json'
            cases_path.write_text(json.dumps({'cases': [case(needs_review=True)]}), encoding='utf-8')
            results_path.write_text(json.dumps({'results': [result()]}), encoding='utf-8')
            proc = subprocess.run([sys.executable, str(SCRIPT), '--cases', str(cases_path),
                                   '--results', str(results_path), '--strict'], capture_output=True, text=True)
            self.assertEqual(1, proc.returncode)
            self.assertFalse(json.loads(proc.stdout)['ready'])

    def test_wrong_carrier_is_failure(self):
        out = E.evaluate({'cases': [case()]}, {'results': [result(carriers=['SKT'])]})
        self.assertTrue(any('unexpected or missing carriers' in message for message in out['failures']))

    def test_required_brand_and_latency_validation_and_zero_cases(self):
        c = case(); c['assertions']['required_brands'] = ['CU']
        out = E.evaluate({'cases': [c]}, {'results': [result()]})
        self.assertTrue(any('required brands absent' in message for message in out['failures']))
        bad_latency = E.evaluate({'cases': [case()]}, {'results': [result(latency_ms=float('inf'))]})
        self.assertFalse(bad_latency['ready'])
        empty = E.evaluate({'cases': [], 'schema_version': 1}, {'results': []})
        self.assertFalse(empty['ready'])


if __name__ == '__main__':
    unittest.main()
