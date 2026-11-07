"""Reproducible PR-0 inventory; metrics are candidates, never dead-code proof."""
import collections
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/evidence/v2/pr0'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    rows, duplicates = [], collections.defaultdict(list)
    for name in filter(None, files):
        if re.search(r'(?i)blind|owner[_-]?only|holdout', name):
            rows.append({'path':name,'classification':'EXCLUDED_OWNER_PRIVATE','read':False})
            continue
        path = ROOT / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        try:
            content = data.decode('utf-8-sig')
        except UnicodeDecodeError:
            continue
        if '/db/migration/' in name:
            classification = 'KEEP_SCHEMA_HISTORY'
        elif '/test/' in name or '/tests/' in name or name.startswith('tests/'):
            classification = 'KEEP_TEST'
        elif name.startswith('config/') or path.suffix in {'.yaml','.yml','.properties'}:
            classification = 'KEEP_REFLECTION_CONFIG'
        else:
            classification = 'NEED_EVIDENCE_KEEP'
        digest = hashlib.sha256(data).hexdigest()
        row = {'path':name,'lines':len(content.splitlines()),'bytes':len(data),'sha256':digest,
               'classification':classification,'deletion_authorized':False,
               'spring_or_runtime_annotations':sorted(set(re.findall(r'@(Service|Component|Configuration|Bean|RestController|Scheduled|EventListener|Value|Autowired|Entity|JsonCreator)',content))),
               'declared_types':len(re.findall(r'\b(?:class|interface|record|enum)\s+\w+',content)),
               'conditional_tokens':len(re.findall(r'\b(?:if|switch|case)\b',content))}
        rows.append(row)
        if len(data) > 100:
            duplicates[digest].append(name)
    (OUT/'file-inventory.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    source = [r for r in rows if r.get('lines') and (r['path'].startswith(('api-java/src/main/','doc-service-py/app/','frontend/src/','config/')))]
    summary = {'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
               'tracked_text_files':len(rows),'source_files':len(source),'source_lines':sum(r['lines'] for r in source),
               'classification_counts':dict(collections.Counter(r['classification'] for r in rows)),
               'exact_duplicate_candidates':[v for v in duplicates.values() if len(v)>1],
               'largest_source_files':[{'path':r['path'],'lines':r['lines']} for r in sorted(source,key=lambda r:r['lines'],reverse=True)[:15]],
               'limitations':['Conditional tokens are not cyclomatic complexity.',
                              'Exact content equality does not prove unused runtime paths.',
                              'Dynamic Spring/config/reflection references require individual reachability review.']}
    (OUT/'inventory-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    failures=[]
    totals=collections.Counter()
    for report in (ROOT/'api-java/target/surefire-reports').glob('TEST-*.xml'):
        suite=ET.parse(report).getroot()
        for field in ('tests','failures','errors','skipped'):
            totals[field]+=int(suite.get(field,0))
        for case in suite.iter('testcase'):
            for kind in ('failure','error'):
                for error in case.findall(kind):
                    failures.append({'class':case.get('classname'),'method':case.get('name'),'kind':kind,
                                     'type':error.get('type'),'message':error.get('message','')[:600]})
    run_log=ROOT/'artifacts/v2-pr0/maven-clean-baseline.log'
    aggregate=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',run_log.read_text(encoding='utf-8',errors='replace'))
    reported=dict(zip(('tests','failures','errors','skipped'),map(int,aggregate[-1]))) if aggregate else {}
    (OUT/'baseline-tests.json').write_text(json.dumps({'maven_reported_totals':reported,'available_xml_totals':dict(totals),
        'counts_agree':reported==dict(totals),'exit_code':1,'command':'mvn -o -f api-java/pom.xml clean test',
        'log_sha256':hashlib.sha256(run_log.read_bytes()).hexdigest(),'failures':failures,
        'limitation':'Maven aggregate and retained XML counts differ; do not claim per-case execution coverage from XML totals.'},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'source_files':len(source),'source_lines':summary['source_lines'],'maven_tests':reported,'xml_tests':dict(totals),'failures':len(failures)},ensure_ascii=False))


if __name__=='__main__':
    main()
