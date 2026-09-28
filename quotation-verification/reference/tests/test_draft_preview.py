import copy
from decimal import Decimal
from conftest import ApiClient


def test_preview_does_not_publish_or_fill_unknowns(admin, rule, product):
    b=copy.deepcopy(rule['body'])
    b.update(effective_from='',tax_basis='',coverage={})
    identifier=admin.call('/resources','POST',{'kind':'rule','body':b}).json['id']
    response=admin.call('/resources/'+identifier+'/preview','POST',{'sample':product})
    assert response.status_code==200, response.json
    assert response.json['preview_only'] is True
    assert response.json['amount']=='200'
    assert response.json['coverage']=={}
    assert response.json['tax_basis']==''
    publish=admin.call('/resources/'+identifier+'/publish','POST',{'sample':product,'expected_amount':'200'})
    assert publish.status_code==400
    saved=next(x for x in admin.call('/resources').json if x['id']==identifier)
    assert saved['status']=='draft'
    assert saved['body']['effective_from']==''


def test_preview_rejects_missing_input_and_quoter(admin,app,rule,product):
    b=copy.deepcopy(rule['body'])
    b['parameters'].pop('unit_cost')
    b['inputs']=[dict(key='unit_cost',label='电机成本',type='number',unit='元',source='input')]
    identifier=admin.call('/resources','POST',{'kind':'rule','body':b}).json['id']
    response=admin.call('/resources/'+identifier+'/preview','POST',{'sample':product})
    assert response.json['status']=='pending'
    assert response.json['amount'] is None
    admin.call('/users','POST',dict(username='preview-quoter',display_name='报价员',password='Test-only-2938!',role='quoter'))
    client=ApiClient(app);client.login('preview-quoter','Test-only-2938!')
    assert client.call('/resources/'+identifier+'/preview','POST',{'sample':product}).status_code==403


def test_imported_example_arithmetic():
    from import_workbook_rules import build
    from quotation.engine import calculate_row
    items=build()
    assert len({b['import_key'] for _,b in items})==len(items)
    expected={'workbook-20260917-v1:duct-dimensions':'156.37401',
              'workbook-20260917-v1:round-flex':'81.64',
              'workbook-20260917-v1:silencer':'292.95',
              'workbook-20260917-v1:plenum':'750'}
    for kind,b in items:
        if b['import_key'] in expected:
            r=calculate_row(b['test_sample'],[dict(id='r',family='r',kind=kind,body=b)],{})
            assert Decimal(r['amount'])==Decimal(expected[b['import_key']])
