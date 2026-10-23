#!/usr/bin/env python3
"""Validate an evaluation corpus and compare adapter-neutral exported results."""
import argparse
import json
import math
import re
import sys

SURFACES = {'chat', 'benefit', 'map', 'recommend'}
EXECUTIONS = {'api', 'controlled_fixture', 'human_review'}


def load(path):
    with open(path, encoding='utf-8') as stream:
        return json.load(stream)


def string_array(value):
    return isinstance(value, list) and all(isinstance(item, str) and item for item in value)


def corpus_errors(corpus):
    if not isinstance(corpus, dict) or not isinstance(corpus.get('cases'), list):
        return ['corpus must be an object with a cases array']
    if not corpus['cases']:
        return ['corpus cases must not be empty']
    errors, seen = [], set()
    for i, case in enumerate(corpus['cases']):
        where = f'cases[{i}]'
        if not isinstance(case, dict):
            errors.append(f'{where} must be an object')
            continue
        case_id = case.get('id')
        if not isinstance(case_id, str) or not case_id.strip():
            errors.append(f'{where}.id must be a nonempty string')
        elif case_id in seen:
            errors.append(f'duplicate case id: {case_id}')
        else:
            seen.add(case_id)
        if case.get('surface') not in SURFACES:
            errors.append(f'{where}.surface is invalid')
        if case.get('execution') not in EXECUTIONS:
            errors.append(f'{where}.execution is invalid')
        if not isinstance(case.get('request'), dict):
            errors.append(f'{where}.request must be an object')
        if 'transport' in case and not isinstance(case['transport'], str):
            errors.append(f'{where}.transport must be a string')
        bindings = case.get('input_bindings', [])
        if not isinstance(bindings, list) or any(not isinstance(x, str) or not x for x in bindings):
            errors.append(f'{where}.input_bindings must be a string array')
        elif bindings and (not isinstance(case.get('request'), dict) or any(x not in case['request'] for x in bindings)):
            errors.append(f'{where}.input_bindings must name request fields')
        request = case.get('request')
        if isinstance(request, dict):
            if 'carrier' in request and (not isinstance(request['carrier'], str) or request['carrier'] not in {'SKT', 'KT', 'LGU', 'MVNO'}):
                errors.append(f'{where}.request.carrier is not a current Carrier enum')
            grades = {'BASIC', 'VIP', 'VVIP', 'VIP콕', 'SKT_LITE', 'SKT_SILVER', 'SKT_GOLD', 'SKT_VIP',
                      'KT_GENERAL', 'KT_WHITE', 'KT_SILVER', 'KT_GOLD', 'KT_VIP', 'KT_VVIP'}
            if 'grade' in request and (not isinstance(request['grade'], str) or request['grade'] not in grades):
                errors.append(f'{where}.request.grade is not a current Grade enum')
        assertions = case.get('assertions')
        if not isinstance(assertions, dict):
            errors.append(f'{where}.assertions must be an object')
            continue
        if not isinstance(assertions.get('needs_review'), bool):
            errors.append(f'{where}.assertions.needs_review must be boolean')
        for field in ('required_brands', 'forbidden_brands', 'carriers'):
            if field in assertions and not string_array(assertions[field]):
                errors.append(f'{where}.assertions.{field} must be a string array')
        if 'status' in assertions and assertions['status'] is not None and not string_array(assertions['status']):
            errors.append(f'{where}.assertions.status must be null or a string array')
        if 'http_status' in assertions and assertions['http_status'] is not None:
            values = assertions['http_status']
            if not isinstance(values, list) or not values or any(not isinstance(v, int) or isinstance(v, bool) for v in values):
                errors.append(f'{where}.assertions.http_status must be a nonempty integer array')
        if not isinstance(case.get('label_status'), str) or not case['label_status']:
            errors.append(f'{where}.label_status must be a nonempty string')
    return errors


def result_errors(result, where):
    errors = []
    if not isinstance(result, dict):
        return [f'{where} must be an object']
    if not isinstance(result.get('case_id'), str) or not result['case_id']:
        errors.append(f'{where} requires case_id string')
    if not isinstance(result.get('input'), dict):
        errors.append(f'{where}.input is required and must be an object')
    if 'http_status' in result and (not isinstance(result['http_status'], int) or isinstance(result['http_status'], bool)):
        errors.append(f'{where}.http_status must be integer')
    for field in ('carriers', 'brands', 'evidence_ids'):
        if field in result and not string_array(result[field]):
            errors.append(f'{where}.{field} must be a string array')
    for field in ('status', 'transport', 'answer_text'):
        if field in result and result[field] is not None and not isinstance(result[field], str):
            errors.append(f'{where}.{field} must be string or null')
    if 'error' in result and result['error'] is not None and not isinstance(result['error'], str):
        errors.append(f'{where}.error must be string or null')
    if result.get('error'):
        errors.append(f"{where} reported error: {result['error']}")
    if 'latency_ms' in result:
        value = result['latency_ms']
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0:
            errors.append(f'{where}.latency_ms must be a finite positive number')
    return errors


def materialization_errors(case):
    errors = []
    for field in case.get('input_bindings', []):
        value = case['request'].get(field)
        if field == 'previousCandidateKeys':
            pattern = r'benefit:[A-Za-z0-9_-]+:policy:[A-Za-z0-9_-]+:tier:(?:[A-Za-z0-9_-]+|policy)'
            if not isinstance(value, list) or not 1 <= len(value) <= 8 or any(
                    not isinstance(key, str) or len(key) > 150 or not re.fullmatch(pattern, key) for key in value):
                errors.append(f'{field} was not materialized with a valid runtime candidate key')
        elif value is None or value == '':
            errors.append(f'{field} was not materialized')
    return errors


def evaluate(corpus, exported):
    errors = corpus_errors(corpus)
    if errors:
        return {'evaluated': [], 'missing': [], 'needs_review': [], 'failures': errors,
                'malformed': [], 'hard_checks': 0, 'complete': False, 'ready': False}
    cases = {case['id']: case for case in corpus['cases']}
    if not isinstance(exported, dict) or not isinstance(exported.get('results'), list):
        return {'evaluated': [], 'missing': sorted(cases), 'needs_review': [], 'failures': [],
                'malformed': ['results file must be object with results array'], 'hard_checks': 0, 'complete': False, 'ready': False}
    results, malformed = {}, []
    for i, result in enumerate(exported['results']):
        result_issues = result_errors(result, f'results[{i}]')
        if result_issues:
            malformed.extend(result_issues)
            continue
        case_id = result['case_id']
        if case_id not in cases:
            malformed.append(f'unknown case_id: {case_id}')
        elif case_id in results:
            malformed.append(f'duplicate result case_id: {case_id}')
        else:
            binding_errors = materialization_errors(cases[case_id])
            if binding_errors:
                malformed.extend(f'{case_id}: {message}' for message in binding_errors)
            elif result['input'] != cases[case_id]['request']:
                malformed.append(f'{case_id}: result.input does not match materialized corpus request')
            else:
                results[case_id] = result
    evaluated, missing, review, failures = [], sorted(set(cases) - set(results)), [], []
    hard_checks = sum(1 for result in results.values() if 'input' in result)
    for case_id, result in results.items():
        case_spec, checks = cases[case_id], cases[case_id]['assertions']
        local = []
        expected_http = checks.get('http_status')
        if expected_http is not None:
            hard_checks += 1
            if 'http_status' not in result:
                local.append('required http_status missing')
            elif result['http_status'] not in expected_http:
                local.append(f"http_status {result['http_status']} not in {expected_http}")
        expected_status = checks.get('status')
        if expected_status is not None:
            hard_checks += 1
            if 'status' not in result:
                local.append('required status missing')
            elif result['status'] not in expected_status:
                local.append(f"status {result['status']!r} not accepted")
        expected_carriers = checks.get('carriers', [])
        if expected_carriers:
            hard_checks += 1
            if 'carriers' not in result:
                local.append('required carriers missing')
            elif not result['carriers'] or not set(result['carriers']).issubset(set(expected_carriers)):
                local.append(f"unexpected or missing carriers: {sorted(set(result['carriers']) - set(expected_carriers))}")
        for field, forbidden in (('required_brands', False), ('forbidden_brands', True)):
            declared = checks.get(field, [])
            if declared:
                hard_checks += 1
                if 'brands' not in result:
                    local.append('brands missing for declared brand check')
                elif forbidden:
                    found = set(declared) & set(result['brands'])
                    if found:
                        local.append(f'forbidden brands present: {sorted(found)}')
                else:
                    absent = set(declared) - set(result['brands'])
                    if absent:
                        local.append(f'required brands absent: {sorted(absent)}')
        failures.extend(f'{case_id}: {message}' for message in local)
        evaluated.append(case_id)
        if checks['needs_review'] or case_spec['label_status'] != 'verified':
            review.append(case_id)
    complete = not missing and not malformed
    ready = complete and not review and not failures
    return {'evaluated': sorted(evaluated), 'missing': missing, 'needs_review': sorted(review),
            'failures': failures, 'malformed': malformed, 'hard_checks': hard_checks,
            'complete': complete, 'ready': ready}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', default='docs/evaluation/search-chat-cases.json')
    parser.add_argument('--results', help='adapter-neutral JSON export; omit to validate corpus only')
    parser.add_argument('--strict', action='store_true', help='fail unless all cases are complete and reviewed')
    args = parser.parse_args(argv)
    try:
        corpus = load(args.cases)
        if args.results:
            result = evaluate(corpus, load(args.results))
        else:
            issues = corpus_errors(corpus)
            result = {'evaluated': [], 'missing': [],
                      'needs_review': [c['id'] for c in corpus['cases']
                                       if isinstance(c, dict) and isinstance(c.get('assertions'), dict)
                                       and c['assertions'].get('needs_review') is True]
                      if isinstance(corpus, dict) and isinstance(corpus.get('cases'), list) else [],
                      'failures': issues, 'malformed': [], 'hard_checks': 0, 'complete': False, 'ready': False}
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({'error': str(exc), 'complete': False, 'ready': False}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    hard_failure = bool(result['failures'] or result['malformed'])
    return 1 if hard_failure or (args.strict and not result['ready']) else 0


if __name__ == '__main__':
    sys.exit(main())
