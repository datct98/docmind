"""Measure explicit cleanup boundaries; never equate line counts or no text matches with reachability proof."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
BASELINE='b3581f01b1b2c189930ac9e20ce33fe569d45c83'
PREFIX='api-java/src/main/'
TARGETS=[PREFIX+'java/com/example/docmind/service/metadata/ChunkMetadataBackfillService.java',
         PREFIX+'java/com/example/docmind/service/salescore/SalesPromptBuilder.java']+[
         PREFIX+'resources/sales/industries/'+c+'.json' for c in ('BEAUTY_SPA','MOTORBIKE_RENTAL','VEHICLE_RENTAL')]


def historical(ref,path):
    return subprocess.check_output(['git','show',ref+':'+path],cwd=ROOT)


def metrics(data):
    text=data.decode('utf-8-sig')
    return dict(lines=len(text.splitlines()),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),
                conditional_tokens=len(re.findall(r'\b(?:if|switch|case)\b',text)))


def test_result(folder, log, pattern='TEST-*.xml'):
    failed=[];totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in folder.glob(pattern):
        suite=ET.parse(path).getroot()
        for key in totals: totals[key]+=int(suite.get(key,0))
        for case in suite.iter('testcase'):
            for kind in ('failure','error'):
                for item in case.findall(kind):
                    failed.append(dict(id=case.get('classname')+'::'+case.get('name'),kind=kind,type=item.get('type'),message=item.get('message','')[:600]))
    raw=log.read_text(encoding='utf-8',errors='replace')
    matches=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',raw)
    reported=dict(zip(totals,map(int,matches[-1]))) if matches else None
    return dict(maven_reported=reported,xml_totals=totals,counts_agree=reported==totals,
                build_success='BUILD SUCCESS' in raw,failures=failed,log_sha256=hashlib.sha256(log.read_bytes()).hexdigest())


def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--tests',action='store_true');p.add_argument('--ready',action='store_true');p.add_argument('--verified',action='store_true');p.add_argument('--candidate-suffix',default='verified');a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True)
    changes=[dict(path=path,before=metrics(historical(BASELINE,path)),after=metrics((ROOT/path).read_bytes())) for path in TARGETS]
    migration_paths=subprocess.check_output(['git','ls-tree','-r','--name-only','2314bf1','api-java/src/main/resources/db/migration'],cwd=ROOT,text=True).splitlines()
    migrations=[dict(path=path,unchanged=historical('2314bf1',path).replace(b'\r\n',b'\n')==(ROOT/path).read_bytes().replace(b'\r\n',b'\n'),
                     comparison='Git-normalized LF; Windows checkout may use CRLF') for path in migration_paths]
    source_files=subprocess.check_output(['git','ls-files','api-java/src/main','frontend/src','doc-service-py/app','config'],cwd=ROOT,text=True).splitlines()
    source=[metrics((ROOT/path).read_bytes()) for path in source_files if (ROOT/path).is_file() and not re.search(r'(?i)blind|holdout|owner[_-]?only|\.env',path)]
    value=dict(parent=BASELINE,targets=changes,source_files=len(source),source_lines=sum(r['lines'] for r in source),
        migrations=migrations,all_historical_migrations_unchanged=all(r['unchanged'] for r in migrations),
        removed_unused_prompt_methods=1,removed_page_specific_constants=2,removed_duplicate_universal_pack_rules=6,
        removed_active_fallback_routes=0,knowledge_authority_cleanup='BLOCKED_ON_VERIFIED_PAGE_BINDING',
        proof_limits=['Conditional tokens are not cyclomatic complexity','Source count grows with new capabilities; no total-complexity reduction claim',
        'Runtime and dynamic reachability judgement is in cleanup-inventory.md','No codebase-clean or quality acceptance from these metrics'])
    (a.output/'cleanup-metrics.json').write_text(json.dumps(value,indent=2),encoding='utf-8')
    if a.tests or a.ready or a.verified:
        initialized=a.ready or a.verified
        before=test_result(ROOT/'artifacts/v2-pr5/baseline-tree/api-java/target/surefire-reports',ROOT/('artifacts/v2-pr5/full-baseline-ready.log' if initialized else 'artifacts/v2-pr5/full-baseline-isolated.log'),'TEST-*-ready.xml' if initialized else 'TEST-*.xml')
        after=test_result(ROOT/'api-java/target/surefire-reports',ROOT/(f'artifacts/v2-pr5/full-current-{a.candidate_suffix}.log' if a.verified else 'artifacts/v2-pr5/full-current-ready.log' if initialized else 'artifacts/v2-pr5/full-current.log'),f'TEST-*-{a.candidate_suffix}.xml' if a.verified else 'TEST-*-ready.xml' if initialized else 'TEST-*.xml')
        before_ids={f['id'] for f in before['failures']};after_ids={f['id'] for f in after['failures']}
        (a.output/(f'full-suite-{a.candidate_suffix}-comparison.json' if a.verified else 'full-suite-ready-comparison.json' if a.ready else 'full-suite-comparison.json')).write_text(json.dumps(dict(baseline=before,candidate=after,
            new_failure_ids=sorted(after_ids-before_ids),resolved_failure_ids=sorted(before_ids-after_ids),
            environment=('Separate databases pre-migrated with all46 unchanged migrations' if initialized else 'Separate initially empty databases')+' on isolated PostgreSQL :55433; same installed JDK/Maven; live provider tests opt-in and skipped'),ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(targets=len(changes),migration_files=len(migrations),migrations_unchanged=value['all_historical_migrations_unchanged'])))


if __name__=='__main__':main()
