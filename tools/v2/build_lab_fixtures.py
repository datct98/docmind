"""Reproducible offline fixtures. Never connects to DB/inbox or imports synthetic facts to a Page."""
import argparse
import hashlib
import json
from pathlib import Path
from prepare_knowledge import prepare


def turn(industry, name, evidence):
    return dict(pageId='isolated-lab-'+industry, businessName=name, industryCode=industry,
                selfPronoun='em', customerPronoun='mình', voice='ấm áp, tự nhiên, gọn và có chính kiến',
                history=[], message='', evidence=evidence, businessPolicies=[], quotedMessage='', contextTruncated=False)


def build(package, destination):
    bundle = prepare(package)
    destination.mkdir(parents=True, exist_ok=True)
    fixtures = {
        'spa': turn('BEAUTY_SPA', 'Nàng Sen', [dict(id=c['id'], title=' / '.join(c['headings']), text=c['text'],
            metadata={k:v for k,v in c['metadata'].items() if k in ('record_kind','branch_tags','service_codes',
                'source_priority','effectiveness','has_source_conflict','review_status')}) for c in bundle['chunks']]),
        'rental': turn('MOTORBIKE_RENTAL', 'An Nhiên', [dict(id='rental-policy', title='Giá và chính sách tại Hoàn Kiếm',
            text='Vision 220k/ngày, Wave 150k/ngày, đã thuế. Một ngày là 24 giờ. Cọc 1000k/xe, tách riêng tiền thuê. '
                 'Mỗi xe kèm 2 mũ bảo hiểm. Khách được kiểm tra tình trạng xe và cùng ghi biên bản trước khi nhận. '
                 'Chưa có tồn xe hoặc phí giao nhận. Không có xác nhận nhận yêu cầu đặt xe từ hệ thống.')]),
        'saas': turn('GENERIC', 'Mây CRM', [dict(id='saas-policy', title='Gói Team',
            text='Team: 790k/tháng cho 5 tài khoản, đã thuế. Có xuất CSV và phân quyền theo vai trò. '
                 'Chưa có thông tin chứng nhận ISO 27001, SSO, API tích hợp hoặc giá cho số lượng khác. '
                 'Chưa có đường dẫn demo được xác nhận. '
                 'Chưa có công cụ tạo tài khoản hay hợp đồng trong hội thoại.')])}
    fixtures['service'] = turn('GENERIC', 'Bảo trì An Tâm', [dict(id='service-policy', title='Bảo trì tiêu chuẩn',
        text='Gói bảo trì tiêu chuẩn 420k/lần, đã thuế, thời lượng dự kiến 2 giờ. Bao gồm vệ sinh và kiểm tra thiết bị theo biên bản. '
             'Linh kiện thay thế báo giá riêng và cần khách đồng ý. Chưa có dữ liệu lịch trống, chưa có công cụ tiếp nhận đơn hay điều phối.')])
    # Counterfactuals change facts inside an isolated fixture; preserve the real source fixture separately.
    counterfactual = json.loads(json.dumps(fixtures['spa']))
    for evidence in counterfactual['evidence']:
        if 'Long Biên' in evidence['title'] and 'NS01 ' in evidence['title']:
            evidence['text'] = 'Cơ sở Long Biên. NS01 Massage cơ bản: 747.000VNĐ, 75 phút, phòng riêng, giá đã bao gồm VAT. Full body massage thư giãn.'
    fixtures['spa-counterfactual'] = counterfactual
    manifest = dict(version=1, production_import_allowed=False, sources=[d['sha256'] for d in bundle['documents']], files={})
    for name, fixture in fixtures.items():
        raw = json.dumps(fixture, ensure_ascii=False, indent=2).encode('utf-8')
        path = destination/(name+'.json'); path.write_bytes(raw)
        manifest['files'][path.name] = dict(sha256=hashlib.sha256(raw).hexdigest(),
            kind='SOURCE_DERIVED_OFFLINE' if name=='spa' else 'SYNTHETIC_COUNTERFACTUAL' if name=='spa-counterfactual' else 'SYNTHETIC')
    (destination/'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return manifest


def main():
    p=argparse.ArgumentParser(); p.add_argument('--package',type=Path,default=Path('artifacts/v2-contract'))
    p.add_argument('--output',type=Path,required=True); a=p.parse_args()
    print(json.dumps(build(a.package,a.output)))


if __name__=='__main__':
    main()
