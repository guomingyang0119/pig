"""Versioned equipment indicators. Values are costs, not cached sale totals."""
from copy import deepcopy
from datetime import date

from .engine import RuleError, number, variables
from .duct import DEFAULT_PAYMENT_TERMS


def default_catalog():
    return {
        'name': '设备供货指标', 'company_id': 'guizhou',
        'effective_from': date.today().isoformat(), 'effective_to': '',
        'source': '设备报价规则确认，2026-09-24；源表按导入记录追溯',
        'rates': {
            'gross_margin': '0.25', 'tax_factor': '1.1', 'round_factor': '1.5',
            'valve_steel': '4500', 'density': '7.85',
            'split_width': '1250', 'split_height': '1000',
            'splice_width': '2000', 'splice_height': '1000',
            'fire_splice': '40', 'manual_splice': '20', 'check_splice': '0',
            'remote_extra': '60', 'near_control': '22', 'remote_control': '39',
            'auto_control': '150', 'panel_rate': '80', 'panel_edge': '60',
            'silencer_rate': '90', 'plenum_rate': '100', 'double_layer_rate': '140',
            'silencer_edge': '50', 'elbow_taxed_rate': '200',
            'freight_threshold': '8000', 'thickness_1_factor': '0.95',
            'thickness_1_2_factor': '0.9', 'angle_factor': '0.95',
        },
        'regions': {'贵州': '1.03', '四川': '1.05', '广西': '1.05',
                    '云南': '1.05', '陕西': '1.06', '海南': '1.1'},
        'payment_terms': deepcopy(DEFAULT_PAYMENT_TERMS),
        'explosion_tiers': [{'limit': '1000', 'factor': '1.4'},
                            {'limit': '2000', 'factor': '1.6'}, {'limit': None, 'factor': '2'}],
        'valves': {
            'fire': {'mechanism': '25', 'blade_thickness': '1.2', 'labor_base': '3'},
            'electric_control': {'mechanism': '35', 'blade_thickness': '1.2', 'labor_base': '3'},
            'electric_fire': {'mechanism': '30', 'blade_thickness': '1.2', 'labor_base': '3'},
            'manual': {'mechanism': '3', 'blade_thickness': '1.2', 'labor_base': '0'},
            'check': {'mechanism': '0', 'blade_thickness': '0.5', 'labor_base': '0'},
            'multi_leaf': {'mechanism': '20', 'blade_thickness': '1.2', 'labor_base': '3'},
            'plate_smoke': {'mechanism': '22', 'blade_thickness': '1.2', 'labor_base': '0'},
        },
        # Each expression computes one component; material prices are supplied by thickness.
        'valve_expressions': {
            'frame': '((width+height)*2+20)/1000*density*frame_thickness*frame_price*0.275/1000',
            'blade': '(width/groups-30)*(height*200+15)*blades*density*blade_thickness*blade_price/1000000000000+(width-30)*density*blade_thickness*0.07*blade_price/1000000',
            'labor': 'blades*7+labor_base',
            'link': 'blades*0.5+(blades-1)*0.2*density*frame_thickness*frame_price*0.035/1000',
            'corners': '8*0.35', 'shaft': '(blades*0.1+0.15)*6',
            'consumables': 'width*height*5/1000000+1',
            'brace': 'height*0.095*brace_count*density*frame_thickness*frame_price/1000000',
            'multi_frame': '((width+height)*2+width)/1000*density*frame_thickness*frame_price*0.25/1000',
            'multi_blade': '(height/groups-280)*(width*200+15)*blades*density*blade_thickness*blade_price/1000000000000+(height-30)*density*blade_thickness*0.07*blade_price/1000000',
            'multi_back': 'width*0.25*density*0.5*back_price/1000000',
            'multi_brace': 'width*0.095*brace_count*density*frame_thickness*frame_price/1000000',
            'plate_frame': '((width+80)+(height+80))*2/1000*0.19*density*frame_thickness*frame_price/1000',
            'plate_blade': '(width+15)*(height+15)/1000000*density*blade_thickness*blade_price/1000',
            'plate_consumables': 'width*height*22/1000000+1',
            'plate_brace': 'width*95*density*frame_thickness*frame_price/1000000000',
        },
        'louvers': {
            'single': ['80', '14', '60', '1'], 'single_valve': ['80', '24', '60', '2'],
            'single_mesh': ['110', '16', '60', '1'], 'double': ['100', '14', '60', '1'],
            'double_valve': ['100', '24', '60', '1.98'], 'double_mesh': ['140', '16', '60', '1'],
            'rain': ['90', '14', '60', '1'], 'rain_mesh': ['120', '16', '60', '1'],
            'gravity': ['90', '14', '60', '1'], 'gravity_valve': ['90', '24', '60', '1.89'],
            'access': ['135', '14', '60', '1'], 'hinged': ['135', '14', '60', '1'],
            'hinged_mesh': ['140', '14', '60', '1'],
            'diffuser': ['160', '16', '70', '1'], 'diffuser_valve': ['160', '26', '70', '1.6'],
            'linear': ['100', '14', '60', '1'], 'linear_valve': ['100', '24', '60', '1.8'],
            'abs_single': ['90', '14', '60', '1'], 'abs_double': ['100', '14', '60', '1'],
            'abs_hinged_mesh': ['120', '14', '60', '1'],
        },
        'materials': [], 'fan_points': [], 'fan_bodies': [], 'motors': [], 'purchased': [],
        'source_files': [],
    }


VALVE_VARIABLES = {'width', 'height', 'groups', 'blades', 'density', 'frame_thickness',
                   'blade_thickness', 'frame_price', 'blade_price', 'back_price',
                   'brace_count', 'labor_base'}


def validate_catalog(body):
    try:
        _validate_catalog(body)
    except (TypeError, KeyError, AttributeError, IndexError) as error:
        raise RuleError('设备指标结构不完整，请保留字段及列表记录结构') from error


def _validate_catalog(body):
    defaults = default_catalog()
    for key in defaults['rates']:
        if number(body.get('rates', {}).get(key)) < 0:
            raise RuleError(f'设备指标不能为负：{key}')
    rates = body['rates']
    if number(rates['check_splice']) != 0: raise RuleError('已确认止回阀不加拼接费，此值必须为0')
    if not 0 <= number(rates['gross_margin']) < 1:
        raise RuleError('设备毛利率必须大于等于0且小于1')
    for key in ('tax_factor', 'round_factor', 'density', 'split_width', 'split_height',
                'splice_width', 'splice_height', 'thickness_1_factor', 'thickness_1_2_factor', 'angle_factor'):
        if number(rates[key]) <= 0:
            raise RuleError(f'设备指标必须大于0：{key}')
    if set(body.get('regions', {})) != set(defaults['regions']):
        raise RuleError('设备运费仅支持已确认六省区')
    for val in body['regions'].values():
        if number(val) < 1: raise RuleError('运费系数不能小于1')
    codes = set()
    for term in body.get('payment_terms', []):
        if term.get('code') in codes or term.get('mode') not in {'no_install', 'with_install'} or number(term.get('coefficient')) <= 0:
            raise RuleError('设备付款方案配置不正确')
        codes.add(term.get('code'))
    if codes != {x['code'] for x in defaults['payment_terms']}:
        raise RuleError('设备付款方案必须与已确认风管方案保持一致')
    for key in defaults['valve_expressions']:
        if variables(body.get('valve_expressions', {}).get(key, '')) - VALVE_VARIABLES:
            raise RuleError(f'阀体成本公式包含未定义变量：{key}')
    for key in defaults['valves']:
        cfg = body.get('valves', {}).get(key, {})
        for field in ('mechanism', 'blade_thickness', 'labor_base'):
            if number(cfg.get(field)) < 0: raise RuleError('阀体成本指标不能为负')
    for key in defaults['louvers']:
        entry = body.get('louvers', {}).get(key)
        if not isinstance(entry, list) or len(entry) != 4 or any(number(x) < 0 for x in entry):
            raise RuleError(f'风口面积成本配置不正确：{key}')
    for kind in ('materials', 'fan_points', 'fan_bodies', 'motors', 'purchased'):
        if not isinstance(body.get(kind), list): raise RuleError(f'设备指标缺少列表：{kind}')
        ids = [x.get('id') for x in body[kind]]
        if not all(ids) or len(ids) != len(set(ids)): raise RuleError(f'{kind}存在空编号或重复编号')
    for row in body['materials']:
        if row.get('material') not in {'201', '304'} or number(row.get('thickness')) <= 0 or number(row.get('ton_price')) <= 0:
            raise RuleError('不锈钢材料价格配置不正确')
    keys = [(x['material'], number(x['thickness'])) for x in body['materials']]
    if len(keys) != len(set(keys)): raise RuleError('同材质板厚只能配置一条吨价')
    for row in body['fan_points']:
        for key in ('size', 'airflow', 'pressure', 'speed'):
            if number(row.get(key)) <= 0: raise RuleError(f'风机参数不正确：{row.get("id")} / {key}')
        if row.get('family') not in {'HTFC', 'HTF(A)', 'SWF'}: raise RuleError('风机系列不正确')
        if not row.get('powers') or any(number(x) <= 0 for x in row['powers']): raise RuleError('风机功率不正确')
        if not row.get('model') or not row.get('poles') or any(number(x) <= 0 for x in row['poles']): raise RuleError('风机型号及极数不完整')
    for row in body['fan_bodies']:
        if number(row.get('steel_price')) <= 0 or number(row.get('size')) <= 0: raise RuleError('风机本体指标不正确')
        for key in ('material_expression', 'other_expression'):
            if variables(row.get(key, '')) - {'steel_price', 'size', 'thickness'}:
                raise RuleError('风机本体公式包含未定义变量')
    for row in body['motors']:
        if number(row.get('cost')) < 0 or not row.get('powers') or not row.get('poles'):
            raise RuleError('电机成本和参数不完整')
        for x in row['powers'] + row['poles']:
            if number(x) <= 0: raise RuleError('电机功率或极数不正确')
    for row in body['purchased']:
        if number(row.get('cost')) < 0 or not row.get('model'): raise RuleError('外购设备基础价不完整')
    tiers = body.get('explosion_tiers', [])
    if not tiers or tiers[-1].get('limit') is not None: raise RuleError('防爆系数缺少最高档')
    last = 0
    for row in tiers:
        if number(row.get('factor')) < 1: raise RuleError('防爆系数不正确')
        if row.get('limit') is not None:
            if number(row['limit']) <= last: raise RuleError('防爆档位必须递增')
            last = number(row['limit'])
