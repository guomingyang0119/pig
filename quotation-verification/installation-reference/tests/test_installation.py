"""Business examples for the confirmed installation and final-order payment rules."""
import io
from copy import deepcopy
from decimal import Decimal as D

import openpyxl
import pytest

from quotation.engine import calculate, RuleError, validate_resource
from quotation.installation import calculate_installation
from quotation.installation_catalog import default_catalog
from quotation.equipment_catalog import default_catalog as equipment_catalog
from quotation.excel_io import write_quote, _formal_duct_lines
from test_duct_pricing import catalog as duct_catalog, quote as duct_quote


def install_resource():
    body = default_catalog()
    body['effective_from'] = '2020-01-01'
    return dict(id='installation', family='installation', kind='installation_catalog', status='published', version=1, body=body)


def equipment_resource():
    body = equipment_catalog()
    body['effective_from'] = '2020-01-01'
    body['purchased'] = [dict(id='purchase', model='TEST', name='测试设备', cost='75', unit='台')]
    return dict(id='equipment', family='equipment', kind='equipment_catalog', status='published', version=1, body=body)


def install_quote(rows=None, **options):
    installation_options = dict(included=True, region='贵州', include_auxiliaries=False, include_site_fee=False)
    installation_options.update(options)
    return dict(duct_options=dict(tax_included=True), installation_options=installation_options,
                rows=rows or [dict(name='共板法兰风管', category='风管', engineering_quantity='10', attributes={})])


def fee(q, resources=None):
    return calculate_installation(q, resources or [install_resource()], [dict() for _ in q['rows']])


def enable(q, region='贵州', payment='with_install_cash'):
    q['installation_options'] = dict(included=True, region=region, include_auxiliaries=False, include_site_fee=False)
    for key in ('duct_options', 'equipment_options'):
        if key in q:
            q[key].update(include_installation=True, payment_option=payment)
    return q


def test_reviewed_source_has_55_rows_and_exact_prices():
    resource = install_resource()
    validate_resource('installation_catalog', resource['body'])
    projects = {p['id']: p for p in resource['body']['projects']}
    assert len(projects) == 55
    assert projects['four_3']['price'] == '30'
    assert projects['four_6']['price'] == '100'
    assert projects['four_16']['price'] == '50'
    assert projects['four_18']['price'] == '20'
    assert projects['four_23']['price'] == '50'
    assert projects['four_24']['price'] == '100'
    assert projects['four_25']['price'] == '35'
    assert projects['four_26']['price'] == '65'
    assert projects['sx_6']['price'] == '110'
    assert projects['sx_19']['power_prices'] == ['300', '600', '800']
    assert projects['sx_20']['source'].endswith('G19:G20')
    resource['body']['tax_factor'] = '1.1'
    with pytest.raises(RuleError, match='1.01'):
        validate_resource('installation_catalog', resource['body'])


@pytest.mark.parametrize('region', ['贵州', '四川', '云南', '广西'])
@pytest.mark.parametrize('name,project,price', [
    ('散流器', 'four_25', '35'),
    ('带阀散流器', 'four_25', '35'),
    ('消声弯头', 'four_26', '65'),
    ('防雨弯头', 'four_26', '65'),
])
def test_four_regions_confirmed_diffuser_and_elbow_prices(region, name, project, price):
    q = install_quote([dict(name=name, unit='个', quantity='2')])
    q['installation_options']['region'] = region
    result, issues = fee(q)
    assert not issues
    assert result['lines'][0]['project_id'] == project
    assert result['lines'][0]['unit_price'] == price


@pytest.mark.parametrize('region', ['贵州', '四川', '云南', '广西'])
def test_four_regions_auxiliaries_and_site_sum_before_tax_and_ceiling(region):
    q = install_quote()
    q['installation_options'].update(region=region, include_auxiliaries=True, include_site_fee=True,
                                     site_amount='0.03', site_note='高空施工附加费')
    q['rows'][0]['engineering_quantity'] = '0.11'
    result, issues = fee(q)
    assert not issues
    assert D(result['labor_amount']) == D('3.3')
    assert D(result['auxiliary_amount']) == D('.88')
    assert D(result['untaxed_amount']) == D('4.21')
    assert result['amount'] == '6'  # line ceil((30*.11 + 8*.11)*1.01) + site ceil(.03*1.01)
    q['installation_options']['include_auxiliaries'] = False
    result, _ = fee(q)
    assert D(result['auxiliary_amount']) == 0 and result['amount'] == '5'


@pytest.mark.parametrize('project,aux', [('sx_3','10'), ('sx_4','12'), ('sx_8','20')])
def test_shaanxi_auxiliary_types(project, aux):
    q = install_quote()
    q['installation_options'].update(region='陕西', include_auxiliaries=True)
    q['rows'][0]['installation'] = dict(project_id=project)
    result, issues = fee(q)
    assert not issues
    assert D(result['auxiliary_amount']) == D(aux)*10


def test_finished_duct_uses_manual_per_square_metre_and_no_area_factor():
    q = enable(duct_quote(), payment='with_install_progress_80')
    q['duct_options'].update(area_basis='net', product_state='finished', tax_included=False)
    q['rows'][0].update(engineering_quantity='2.5', installation={'unit_price':'7.2'})
    result = calculate(q, [duct_catalog(), install_resource()], {'formal_template_confirmed':True})
    installation = result['fees']['installation']
    assert D(installation['untaxed_amount']) == 18  # no 1.14 area conversion, no margin
    assert installation['amount'] == '18'  # untaxed order rounds this installation line directly
    assert D(result['total']) == ((D(result['subtotal']) + 18)*D('1.1')).to_integral_value(rounding='ROUND_CEILING')
    q['rows'][0]['installation'] = {'project_id':'four_4'}
    assert calculate(q, [duct_catalog(), install_resource()], {})['total'] is None


@pytest.mark.parametrize('power,price', [('1.49','300'), ('1.5','600'), ('17.5','600'), ('17.51','800')])
def test_fan_power_boundaries(power, price):
    q = install_quote([dict(name='排烟风机', unit='台', quantity='2', attributes={'power':power})])
    q['installation_options']['region'] = '陕西'
    result, issues = fee(q)
    assert not issues
    assert result['lines'][0]['unit_price'] == price
    assert D(result['amount']) == D(price)*2*D('1.01')


def test_dual_speed_and_ambiguous_names_require_manual_confirmation():
    q = install_quote([dict(name='排烟风机', unit='台', quantity='1', attributes={'power':'18.5/15'})])
    q['installation_options']['region'] = '陕西'
    assert '双速' in fee(q)[1][0]
    q['rows'][0]['installation'] = {'unit_price':'700'}
    assert fee(q)[0]['amount'] == '707'
    q = install_quote([dict(name='防雨百叶风口', unit='个', quantity='2')])
    assert fee(q)[0]['status'] == 'pending'
    q['rows'][0]['installation'] = {'project_id':'four_23'}
    assert fee(q)[0]['amount'] == '101'


def test_units_never_silently_convert_between_device_count_and_area():
    q = install_quote([dict(name='防火阀', unit='个', quantity='10')])
    q['installation_options']['region'] = '陕西'
    assert '单位不同' in fee(q)[1][0]
    q['rows'][0]['installation'] = {'project_id':'sx_23', 'quantity':'8', 'unit':'套'}
    assert fee(q)[0]['amount'] == '445'  # ceil(8*55*1.01)
    q['rows'][0]['installation']['unit'] = '㎡'
    assert '单位与所选项目不一致' in fee(q)[1][0]


@pytest.mark.parametrize('region,height', [('贵州','5.01'), ('陕西','4.01')])
def test_site_conditions_only_charge_after_the_quoter_selects_it(region, height):
    q = install_quote(include_site_fee=False, site_amount='', height=height)
    q['installation_options']['region'] = region
    q['rows'][0]['installation'] = {'unit_price':'30'}
    assert not fee(q)[1]
    q['installation_options'].update(height='0', cross_work=True, include_site_fee=True)
    assert '请填写未税现场附加费' in fee(q)[1][0]
    q['installation_options']['site_amount'] = '0'
    assert not fee(q)[1]


def test_positive_site_amount_requires_reason():
    q = install_quote(include_site_fee=True, site_amount='3.2')
    q['rows'][0]['installation'] = {'unit_price':'30'}
    assert '填写附加原因' in fee(q)[1][0]
    q['installation_options']['site_note'] = '交叉施工附加费'
    assert not fee(q)[1]


def test_manual_uncovered_region_and_independent_wrap_item():
    q = install_quote()
    q['installation_options']['region'] = '海南'
    assert fee(q)[0]['amount'] is None
    q['rows'][0]['installation'] = {'unit_price':'20'}
    assert fee(q)[0]['amount'] == '202'
    q['installation_options'].update(region='贵州', include_auxiliaries=True)
    q['installation_items'] = [dict(name='防火包裹', project_id='four_11', quantity='2', unit='㎡')]
    result, issues = fee(q)
    assert not issues
    assert D(result['auxiliary_amount']) == 80  # only the 10㎡ duct, never wrap
    assert D(result['untaxed_amount']) == 200+80+76
    assert result['amount'] == '360'
    q['installation_items'][0]['name'] = '风管防火包裹'
    assert fee(q)[0]['auxiliary_amount'] == '80'  # name alone must not add a duct package to wrap


def test_included_device_components_do_not_add_duct_auxiliary_package():
    q = install_quote([dict(name='远控常闭排烟阀', unit='个', quantity='1', installation={'project_id':'sx_24'})])
    q['installation_options'].update(region='陕西', include_auxiliaries=True)
    result, issues = fee(q)
    assert not issues
    assert result['auxiliary_amount'] == '0' and result['amount'] == '112'


def test_confirmed_combination_and_remote_control_installation_rules():
    q = install_quote([dict(name='轴流风机带防雨百叶', unit='台', quantity='2')])
    result, issues = fee(q)
    assert not issues
    assert result['lines'][0]['project_id'] == 'four_13'
    assert result['lines'][0]['unit_price'] == '500'

    q = install_quote([dict(name='单层百叶带阀风口', unit='个', quantity='2')])
    result, issues = fee(q)
    assert not issues
    assert result['lines'][0]['project_id'] == 'four_18'
    assert result['lines'][0]['unit_price'] == '20'

    q = install_quote([dict(name='远控防火阀', unit='个', quantity='2')])
    result, issues = fee(q)
    assert not issues
    line = result['lines'][0]
    assert line['base_unit_price'] == '50'
    assert line['remote_control_unit_price'] == '65'
    assert line['unit_price'] == '115'

    q['rows'][0]['name'] = '电动防火阀'
    result, issues = fee(q)
    assert not issues
    assert result['lines'][0]['unit_price'] == '50'
    assert result['lines'][0]['remote_control_unit_price'] == '0'


def test_catalog_publication_and_retirement_never_fall_back():
    resource = install_resource()
    draft = deepcopy(resource); draft['status'] = 'draft'
    assert fee(install_quote(), [draft])[0]['amount'] is None
    retired = deepcopy(resource); retired.update(version=2, status='retired')
    assert fee(install_quote(), [resource, retired])[0]['amount'] is None


def test_legacy_whole_amount_is_not_silently_multiplied_by_area():
    q = duct_quote(); q['duct_options'].update(include_installation=True, installation_amount='1000', payment_option='with_install_cash')
    result = calculate(q, [duct_catalog(), install_resource()], {})
    assert result['total'] is None
    assert any('旧版整单' in issue for issue in result['issues'])


def test_mixed_order_installs_and_settles_exactly_once():
    q = enable(duct_quote(), payment='with_install_progress_80')
    q['pricing_mode'] = 'mixed_v1'
    q['rows'][0].update(engineering_quantity='2.5', installation={'unit_price':'10'})
    q['equipment_options'] = dict(payment_option='with_install_progress_80', tax_included=False, include_freight=False)
    q['rows'].append(dict(name='测试设备', category='外购设备', quantity='2', unit='台', attributes={'model':'TEST'}, installation={'unit_price':'7'}))
    result = calculate(q, [duct_catalog(), equipment_resource(), install_resource()], {'formal_template_confirmed':True})
    assert result['formal_ready'], result['issues']
    assert D(result['subtotal']) == D('278.5')
    assert result['fees']['installation']['amount'] == '39'  # untaxed rows: ceil(25) + ceil(14)
    assert result['before_payment_total'] == '317.5'
    assert result['total'] == '350'  # ceil(317.5*1.1), no group rounding/payment
    lines = _formal_duct_lines(q, result, True)
    assert sum(D(str(line['material_amount'] or 0))+D(str(line['installation_amount'] or 0)) for line in lines) == 350
    assert sum(D(str(line['installation_amount'] or 0)) for line in lines) == 39
    wb = openpyxl.load_workbook(io.BytesIO(write_quote(q, result, 'INSTALL-TEST', formal=True)), data_only=True)
    ws = wb.active
    assert ws.title == '带安装'
    texts = '\n'.join(str(cell.value) for row in ws for cell in row if cell.value is not None)
    assert '不作用于安装' not in texts and '整单付款调整及取整' in texts
    assert '成本' not in texts and '毛利' not in texts
    wb.close()


def test_freight_threshold_includes_installation_and_payment_coefficient():
    q = dict(pricing_mode='equipment_v1', equipment_options=dict(payment_option='with_install_progress_70', tax_included=False, include_freight=True, region='贵州'),
             rows=[dict(name='测试设备', category='外购设备', quantity='70', unit='台', attributes={'model':'TEST'}, installation={'unit_price':'1000'})])
    enable(q, payment='with_install_progress_70')
    resources = [equipment_resource(), install_resource()]
    result = calculate(q, resources, {'formal_template_confirmed':True})
    assert '达到8000' in result['fees']['equipment_freight']['reason']
    assert result['fees']['equipment_freight']['calculated_amount'] == '210'
    assert result['fees']['equipment_freight']['basis'] == 'regional_per_unit'
    assert result['rows'][0]['unit_price'] == '103'
    assert D(result['subtotal']) == 7210 and result['total'] == '92652'


def test_installation_api_publish_permissions_and_persistence(admin):
    seed = admin.call('/installation/catalog-seed').json
    seed['effective_from'] = '2020-01-01'
    response = admin.call('/resources', 'POST', {'kind':'installation_catalog', 'body':seed})
    assert response.status_code == 200
    resource_id = response.json['id']
    assert admin.call(f'/resources/{resource_id}/publish', 'POST', {}).status_code == 200
    quote_id = admin.call('/quotes', 'POST', {'pricing_mode':'equipment_v1'}).json['id']
    q = admin.call('/quotes/'+quote_id).json
    body = q['body']; body['installation_options'].update(included=True, region='四川', include_site_fee=True,
                                                          site_amount='3.2', site_note='高空施工附加费')
    body['equipment_options']['payment_option'] = 'with_install_cash'
    body['rows'] = [dict(name='人工设备', unit='台', quantity='2', attributes={}, installation={'unit_price':'20.1'})]
    body['installation_items'] = [dict(name='包裹', project_id='four_9', quantity='5', unit='㎡')]
    saved = admin.call('/quotes/'+quote_id, 'PUT', {**body, 'revision':q['revision']})
    assert saved.status_code == 200, saved.json
    loaded = admin.call('/quotes/'+quote_id).json
    assert loaded['body']['rows'][0]['installation']['unit_price'] == '20.1'
    assert loaded['body']['installation_items'][0]['quantity'] == '5'
    assert loaded['body']['equipment_options']['include_installation'] is True
    partial_body = deepcopy(loaded['body'])
    partial_body['rows'][0].pop('installation')
    preserved = admin.call('/quotes/'+quote_id, 'PUT', {**partial_body, 'revision':loaded['revision']})
    assert preserved.status_code == 200
    loaded = admin.call('/quotes/'+quote_id).json
    assert loaded['body']['rows'][0]['installation']['unit_price'] == '20.1'
    body['installation_options']['included'] = 'true'
    assert admin.call('/quotes/'+quote_id, 'PUT', {**body, 'revision':loaded['revision']}).status_code == 400


def test_mixed_payment_catalog_conflict_is_not_silently_resolved():
    q = enable(duct_quote(), payment='with_install_progress_80')
    q['pricing_mode'] = 'mixed_v1'
    q['equipment_options'] = dict(payment_option='with_install_progress_80', tax_included=False, include_freight=False)
    q['rows'].append(dict(name='测试设备', category='外购设备', quantity='1', unit='台', attributes={'model':'TEST'}, installation={'unit_price':'10'}))
    equipment = equipment_resource()
    next(t for t in equipment['body']['payment_terms'] if t['code']=='with_install_progress_80')['coefficient'] = '1.2'
    result = calculate(q, [duct_catalog(), equipment, install_resource()], {'formal_template_confirmed':True})
    assert result['total'] is None
    assert any('付款系数不一致' in issue for issue in result['issues'])


def test_explicit_finished_description_cannot_fall_back_to_half_finished_table():
    q = install_quote()
    q['rows'][0]['name'] = '成品共板法兰风管'
    q['rows'][0]['installation'] = {'product_state':'semi'}
    assert '成品风管' in fee(q)[1][0]
