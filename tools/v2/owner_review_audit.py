"""Read ONLY the supplied development workbook; reconcile its 30 owner labels."""
import collections
import hashlib
import json
import pathlib
import openpyxl

BASE = pathlib.Path('artifacts/v2-contract/assets/owner-reviewed')
OUT = pathlib.Path('docs/evidence/v2/pr0')
ASSERTIONS = {
 'SPA-DEV-001':['EARLY_SCOPED_PRICE','ONE_USEFUL_QUESTION','SPECIFIC_WARMTH'],
 'SPA-DEV-002':['DIRECT_PRICE_TERMS','NO_DISCOVERY_OR_CTA'],
 'SPA-DEV-003':['FOLLOW_GOAL_AFTER_LOCATION','NO_DEFAULT_UPSELL','NO_UNPROMPTED_MEDICAL_DISCLAIMER'],
 'SPA-DEV-004':['APOLOGY_PLUS_USEFUL_ANSWER','NO_REPEATED_DISCOVERY'],
 'SPA-DEV-005':['VALUE_PROOF','NO_ASSUMED_BUDGET','NO_DEFENSIVE_DISCLAIMER'],
 'SPA-DEV-006':['QUALITY_CONCERN_PROOF','NO_DOWNSALE','NO_INTERNAL_SOURCE_PROSE'],
 'SPA-DEV-007':['SCOPED_ROOM','REQUESTED_ALTERNATIVE_WITH_PRICE'],
 'SPA-DEV-008':['PAUSE_NO_FOLLOWUP','RESUME_WITH_MEMORY'],
 'SPA-DEV-009':['APOLOGY_PLUS_USEFUL_ANSWER','STOP_NO_MORE_MESSAGES'],
 'SPA-DEV-010':['BUYING_SIGNAL_NEXT_STEP','RECEIPT_BEFORE_CONFIRMATION','CONTACT_CONSENT'],
 'SPA-DEV-011':['PROMO_SCOPE_AND_VALIDITY','NO_UNSOLICITED_PROMO'],
 'SPA-DEV-012':['NO_HEALING_CLAIM','WARM_MEDICAL_BOUNDARY'],
 'SPA-DEV-013':['SERVICE_COMPLAINT','NO_INTERNAL_LABEL','RECEIPT_BEFORE_HANDOFF'],
 'SPA-DEV-014':['SCOPED_PRICE_ROOM_VAT','ANSWER_BOTH_QUESTIONS'],
 'RENTAL-DEV-001':['PRESERVE_SELECTED_CATEGORY','NO_TEST_JARGON'],
 'RENTAL-DEV-002':['NO_FAKE_INVENTORY','ONLY_NEEDED_OPERATIONAL_QUESTION'],
 'SAAS-DEV-001':['NO_INVENTED_PRICE','HELP_WITH_KNOWN_INFORMATION'],
 'SAAS-DEV-002':['NO_INVENTED_CERTIFICATION','NO_TEST_JARGON'],
 'SERVICE-DEV-001':['PAUSE_NO_FOLLOWUP','RESUME_WITH_MEMORY'],
 'SERVICE-DEV-002':['BUYING_SIGNAL_NEXT_STEP','RECEIPT_BEFORE_CONFIRMATION'],
 'PAIR-001':['EARLY_SCOPED_PRICE','BRIEF_GREETING_WITH_VALUE'],
 'PAIR-002':['FOLLOW_GOAL_AFTER_LOCATION'],
 'PAIR-003':['APOLOGY_PLUS_USEFUL_ANSWER'],
 'PAIR-004':['QUALITY_CONCERN_PROOF','NO_DOWNSALE'],
 'PAIR-005':['STOP_NO_MORE_MESSAGES'],
 'PAIR-006':['SCOPED_ROOM'],
 'PAIR-007':['DIRECT_PRICE_TERMS','NO_DISCOVERY_OR_CTA'],
 'PAIR-008':['RECEIPT_BEFORE_CONFIRMATION'],
 'PAIR-009':['PRESERVE_SELECTED_CATEGORY'],
 'PAIR-010':['PAUSE_NO_FOLLOWUP'],
}


def main():
    book_path=BASE/'DocMind_Duyet_Hoi_Thoai_Danh_Cho_Ban-da-duyet.xlsx'
    workbook=openpyxl.load_workbook(book_path,read_only=True,data_only=True)
    labels={r['reviewId']:r for r in map(json.loads,(BASE/'owner_review_labels.jsonl').read_text(encoding='utf-8-sig').splitlines())}
    result=[]
    for sheet in ('01 NANG SEN','02 DA NGANH','03 DOI CHIEU'):
        for index,row in enumerate(workbook[sheet].iter_rows(min_row=2,values_only=True),2):
            pair=sheet=='03 DOI CHIEU'
            case_id=row[0]
            status=row[6 if pair else 5]
            label=labels[case_id]
            assert label['status']==status and label['excelRow']==index and label['sheet']==sheet
            assert label['originalDialogueOrCustomer']==row[3]
            assert label['ownerFeedback']==(row[7 if pair else 6] or '')
            if not pair:
                assert label['ownerDesiredChange']==(row[7] or '')
            result.append({'scenario_id':case_id,'sheet':sheet,'row':index,'ownerDecision':status,
                           'title':label['contextTitle'],'expectedBehavior':ASSERTIONS[case_id],
                           'ownerRationale':label['ownerFeedback'],
                           'desiredChange':label.get('ownerDesiredChange',''),
                           'positive_example_eligible':status=='GIU',
                           'literal_target':False,'execution':'NOT_RUN',
                           'test_contract':'v2-lab:'+case_id,'sourceProvenance':'SYNTHETIC_OWNER_REVIEWED',
                           'workbook_sha256':hashlib.sha256(book_path.read_bytes()).hexdigest()})
    assert len(result)==30 and set(ASSERTIONS)=={r['scenario_id'] for r in result}
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'owner-coverage.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'reconciled':len(result),'status':dict(collections.Counter(r['ownerDecision'] for r in result)),
                      'owner_holdout_read':False,'quality_evaluation':'NOT_RUN'},ensure_ascii=False))


if __name__=='__main__':
    main()
