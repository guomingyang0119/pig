"""Equipment supply pricing with Decimal costs and exact rational final pricing."""
import re
from decimal import Decimal, ROUND_CEILING, localcontext
from fractions import Fraction

from .engine import RuleError, active_catalog, evaluate, number, text_number as decimal_text
from .equipment_catalog import validate_catalog
from .equipment_fans import FanSelectionError, material_price, normalize_model, select_fan

EQUIPMENT_ENGINE_VERSION = 'equipment-v1'
MIXED_ENGINE_VERSION = 'mixed-v1'
KINDS = {
    'fan': '风机', 'fire': '防火阀', 'electric_fire': '电动防火阀',
    'electric_control': '电动调节阀', 'manual': '手动调节阀', 'check': '止回阀',
    'pressure_relief': '余压阀', 'multi_leaf': '多叶排烟口/组合送风口',
    'plate_smoke': '板式排烟口', 'louver': '百叶风口', 'silencer': '消声器',
    'plenum': '静压箱/接管箱', 'elbow': '消声弯头', 'purchased': '外购设备',
}


def ceil(value):
    if isinstance(value, Fraction): return Decimal(-(-value.numerator//value.denominator))
    return value.to_integral_value(rounding=ROUND_CEILING)


def text_number(value):
    # Decimal strings are display/audit values. Decisions use exact rational pricing.
    if isinstance(value, Fraction): value = Decimal(value.numerator)/Decimal(value.denominator)
    return decimal_text(value)


def classify(row):
    a = row.get('attributes', {})
    if a.get('equipment_kind'): return a['equipment_kind']
    text = str(row.get('name', ''))
    if '风机' in text or re.match(r'^\s*(?:HTF|HTFC|SWF|GXF|HL3)[-（(]', text, re.I): return 'fan'
    if any(w in text for w in ('通风器','换气扇')): return 'purchased'
    for words, kind in [
        (('消声弯头', '消音弯头'), 'elbow'), (('接管箱', '静压箱'), 'plenum'),
        (('消声器', '消音器', '消声静压箱'), 'silencer'),
        (('板式排烟',), 'plate_smoke'), (('多叶排烟', '多叶送风口', '正压送风口', '正压送风阀', '组合送风口'), 'multi_leaf'),
        (('余压',), 'pressure_relief'), (('止回',), 'check'),
        (('电动防火',), 'electric_fire'), (('防火阀', '排烟阀', '防烟阀'), 'fire'),
        (('电动调节', '电动风量'), 'electric_control'), (('调节阀', '调节风阀'), 'manual'),
        (('风机', '通风机'), 'fan'),
        (('百叶', '散流器', '检修口', '检修门', '条缝风口', '线形风口'), 'louver'),
    ]:
        if any(w in text for w in words): return kind
    return 'purchased' if row.get('category') == '外购设备' else ''


def parse_attributes(row):
    """Only extract explicit labelled data; missing inputs stay missing."""
    a = {k: v for k, v in row.get('attributes', {}).items() if v not in (None, '')}
    text = ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark'))
    # +250 denotes the control-window panel, not an extra 250mm of valve body.
    panel_dims = re.search(r'(\d+(?:\.\d+)?)\s*[×xX*＊]\s*[（(]\s*(\d+(?:\.\d+)?)\s*\+\s*250\s*[）)]', text)
    if panel_dims and classify(row) in {'multi_leaf','plate_smoke'}:
        a.setdefault('width', panel_dims[1]); a.setdefault('height', panel_dims[2])
    dims = re.search(r'(\d+(?:\.\d+)?)\s*[×xX*＊]\s*(\d+(?:\.\d+)?)(?:\s*[×xX*＊]\s*(\d+(?:\.\d+)?))?', text)
    if dims:
        for k, val in zip(('width', 'height', 'length'), dims.groups()):
            if val: a.setdefault(k, val)
    diameter = re.search(r'(?:[ΦφØø]|DN|直径\s*[:：=]?)\s*(\d+(?:\.\d+)?)', text, re.I)
    if diameter: a.setdefault('diameter', diameter[1])
    if a.get('diameter'):
        a['width'] = a['diameter']; a['height'] = a['diameter']; a.setdefault('shape', 'round')
    if '圆形' in text or '圆型' in text: a.setdefault('shape', 'round')
    for grade in ('304', '201'):
        if grade in text and ('不锈' in text or re.search(grade+r'\s*(?:钢|SS)', text, re.I)):
            a.setdefault('material', 'stainless_'+grade)
    if '不锈钢' in text: a.setdefault('material', '不锈钢')
    if '防爆' in text: a.setdefault('explosion', 'true')
    if '远控' in text: a.setdefault('remote', 'true')
    if '双层' in text: a.setdefault('layer', 'double')
    if '角钢' in text: a.setdefault('flange', 'angle')
    if '喉部' in text: a.setdefault('dimension_basis', 'throat')
    patterns = {
        'airflow': r'(?:风量|(?<![A-Z])(?:Q|L)\s*[:：=＝])\s*[:：=＝]?\s*(\d+(?:\.\d+)?)',
        'pressure': r'(?:全压|静压|风压|(?<![A-Z])(?:P|H)\s*[:：=＝])\s*[:：=＝]?\s*(\d+(?:\.\d+)?)',
        'speed': r'(?:转速\s*[:：=＝]?\s*|(?<![A-Z])N\s*[:：=＝]\s*)(\d+(?:\.\d+)?)\s*(?:r/min|rpm|转/分)',
        'thickness': r'(?:厚度|板厚|厚)\s*[:：=＝]?\s*(\d+(?:\.\d+)?)',
        'power': r'(\d+(?:\.\d+)?(?:\s*/\s*\d+(?:\.\d+)?)?)\s*(?:kW|千瓦)',
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, text, re.I)
        if match: a.setdefault(key, match[1].replace(' ', ''))
    for key in ('airflow','pressure'):
        pair = re.search(patterns[key]+r'\s*/\s*(\d+(?:\.\d+)?)', text, re.I)
        if pair: a.setdefault(key+'_low', pair[2])
    model = re.search(r'\b(?:HTFC|HTF(?:\([A-Z]\))?|SWF|GXF|HL3)[-－A-ZIⅠⅡ\d.()]*', text, re.I)
    if classify(row) == 'purchased':
        model = re.search(r'\b[A-Z]{2,}[A-Z\d]*[-－][A-Z\d.－-]+', text, re.I)
    if 'power' not in a:
        watts = re.search(r'(\d+(?:\.\d+)?)\s*W\b', text, re.I)
        if watts: a['power'] = text_number(number(watts[1])/1000)
    if model: a.setdefault('model', model[0])
    if a.get('model'): a['raw_model'] = a['model']
    return a


def positive(a, key, label):
    if a.get(key) in (None, ''): raise RuleError('请补充'+label)
    val = number(a[key])
    if val <= 0: raise RuleError(label+'必须大于0')
    return val


def enabled(value):
    return str(value).lower() in {'true', '1', '是', '有'}


def valve_cost(kind, a, catalog):
    rates = {k: number(v) for k, v in catalog['rates'].items()}
    proxy = kind == 'pressure_relief'
    kind = 'fire' if proxy else kind
    cfg = catalog['valves'][kind]
    w, h = positive(a, 'width', '宽度（mm）'), positive(a, 'height', '高度（mm）')
    split = w > rates['split_width'] or h > rates['split_height']
    groups = Decimal(2 if split else 1)
    blades = ceil((w if kind == 'multi_leaf' else h)/200)*groups
    ft = positive({'thickness': a.get('thickness', '1.2')}, 'thickness', '阀体板厚')
    bt = positive({'thickness': a.get('blade_thickness', cfg['blade_thickness'])}, 'thickness', '叶片板厚')
    steel = rates['valve_steel']
    env = dict(width=w, height=h, groups=groups, blades=blades, density=rates['density'],
               frame_thickness=ft, blade_thickness=bt, labor_base=number(cfg['labor_base']),
               brace_count=Decimal(2 if not split or kind == 'manual' else 4),
               frame_price=material_price(catalog, a.get('material'), ft, steel),
               blade_price=material_price(catalog, a.get('material'), bt, steel),
               back_price=material_price(catalog, a.get('material'), '.5', steel) if kind == 'multi_leaf' else steel)
    expr = catalog['valve_expressions']
    keys = ['frame', 'blade', 'labor', 'link', 'corners', 'shaft', 'consumables', 'brace']
    if kind == 'multi_leaf': keys = ['multi_frame', 'multi_blade', 'labor', 'link', 'shaft', 'consumables', 'multi_brace', 'multi_back']
    if kind == 'plate_smoke': keys = ['plate_frame', 'plate_blade', 'labor', 'link', 'corners', 'shaft', 'plate_consumables', 'plate_brace']
    if kind == 'plate_smoke':
        blades = ceil(h/200)
        env['blades'] = blades
    parts = {key: evaluate(expr[key], env) for key in keys}
    if kind == 'plate_smoke': parts['link'] = parts['shaft'] = Decimal(0)
    if kind == 'check':
        parts['link'] = Decimal(0)
        if not split: parts['blade'] *= Decimal('1.2')
    if kind == 'multi_leaf' and split: parts['shaft'] *= 2
    mechanism = number(cfg['mechanism'])
    control = a.get('control', 'near')
    if control not in {'near', 'remote', 'auto'}: raise RuleError('机构方式未识别')
    if kind == 'plate_smoke': mechanism = rates[control+'_control']
    if a.get('mechanism_cost') not in (None, ''): mechanism = number(a['mechanism_cost'])
    if enabled(a.get('remote')) or control == 'remote': mechanism += rates['remote_extra']
    parts['mechanism'] = mechanism
    splice = Decimal(0)
    if kind in {'fire', 'electric_fire', 'electric_control', 'manual'}:
        over_w, over_h = w > rates['splice_width'], h > rates['splice_height']
        if over_w and over_h:
            if a.get('splice_cost') in (None, ''): raise RuleError('宽超过2000且高超过1000，请报价人员确认拼接费用')
            splice = number(a['splice_cost'])
        elif over_w or over_h: splice = rates['manual_splice' if kind == 'manual' else 'fire_splice']
    parts['splice'] = splice  # Check valves never attract a splice charge.
    if kind in {'multi_leaf', 'plate_smoke'}:
        parts['panel'] = (w+rates['panel_edge'])*(h+rates['panel_edge'])*rates['panel_rate']/1000000
    if any(v < 0 for v in parts.values()): raise RuleError('尺寸或成本参数使阀体成本为负，请核对')
    return sum(parts.values(), Decimal(0)), {
        'components': {k: text_number(v) for k, v in parts.items()},
        'split': split, 'groups': text_number(groups), 'blades': text_number(blades),
        'price_proxy': '对应规格防火阀' if proxy else None,
        'material_prices': {k: text_number(env[k]) for k in ('frame_price', 'blade_price')},
    }


def louver_type(row, a):
    if a.get('louver_type'): return a['louver_type']
    text = str(row.get('name', ''))+' '+str(row.get('spec', ''))
    base = next((kind for word, kind in [('散流','diffuser'),('防雨','rain'),('自垂','gravity'),('门铰','hinged'),('检修','access'),('条缝','linear'),('线形','linear'),('双层','double'),('单层','single')] if word in text), '')
    if not base: raise RuleError('请明确风口类型（单层、双层、散流器等）')
    if 'ABS' in text.upper():
        return 'abs_'+base+('_mesh' if base == 'hinged' and '网' in text else '')
    if '阀' in text and '网' in text: raise RuleError('同时带阀和网的风口需明确组合成本，不能只套其中一种')
    if '阀' in text: base += '_valve'
    elif '网' in text: base += '_mesh'
    return base


def area_cost(kind, row, a, catalog):
    w, h = positive(a, 'width', '宽度（mm）'), positive(a, 'height', '高度（mm）')
    rates = {k: number(v) for k, v in catalog['rates'].items()}
    # These sources specify a composite area cost, not a sheet-metal mass formula.
    # An explicit reviewed area cost is required for an unrepresented material.
    stainless = '不锈' in a.get('material', '') or 'stainless' in a.get('material', '') or a.get('material') in {'201', '304'}
    if stainless and not a.get('area_cost'):
        raise RuleError('该产品采用综合面积成本，源表未分解不锈钢用量；请提供已核实的每㎡成本')
    if kind == 'louver':
        typ = louver_type(row, a)
        if typ not in catalog['louvers']: raise RuleError('该风口类型尚无成本依据，请核对类型')
        rate, minimum, edge, factor = map(number, catalog['louvers'][typ])
        if a.get('dimension_basis', 'opening') not in {'opening', 'throat'}: raise RuleError('尺寸口径未识别')
        if a.get('dimension_basis') == 'throat' and typ.startswith('diffuser'): edge = Decimal(130)
        area = (w+edge)*(h+edge)/1000000
        cost = max(area*number(a.get('area_cost', rate))*factor, minimum)
        return cost, {'area': text_number(area), 'louver_type': typ, 'minimum_cost': text_number(minimum)}
    length = positive(a, 'length', '长度（mm）')
    if kind == 'silencer': w += 2*rates['silencer_edge']; h += 2*rates['silencer_edge']
    area = ((w+h)*length*2+w*h*2)/1000000
    layer = a.get('layer', 'single')
    if layer not in {'single', 'double'}: raise RuleError('请明确单层或双层')
    rate = rates['double_layer_rate'] if layer == 'double' else rates['silencer_rate' if kind == 'silencer' else 'plenum_rate']
    cost = area*number(a.get('area_cost', rate))
    thickness = number(a.get('thickness', '.75'))
    if thickness == 1: cost /= rates['thickness_1_factor']
    elif thickness == Decimal('1.2'): cost /= rates['thickness_1_2_factor']
    elif thickness != Decimal('.75'): raise RuleError('该板厚没有已确认的消声设备成本系数')
    if a.get('flange') == 'angle': cost /= rates['angle_factor']
    return cost, {'area': text_number(area), 'area_rate': text_number(number(a.get('area_cost', rate))), 'layer': layer}


def purchased_cost(row, a, catalog):
    model = normalize_model(a.get('model') or row.get('spec'))
    matches = [p for p in catalog['purchased'] if normalize_model(p['model']) == model]
    if row.get('candidate_id'): matches = [p for p in matches if p['id'] == row['candidate_id']]
    if len(matches) != 1: raise RuleError('外购设备型号缺少唯一基础成本，请在指标中补齐或明确型号')
    item = matches[0]
    if item.get('unit') and item['unit'] != row.get('unit'): raise RuleError('外购设备计价单位与基础价格不一致')
    return number(item['cost']), {'purchased': item}


def calculate_row(row, catalog, options, resource):
    out = dict(status='pending', reason='', unit_price=None, amount=None, trace=[], candidates=[], pricing_mode='equipment_v1')
    if row.get('excluded'):
        if row.get('exclude_reason', '').strip(): out.update(status='excluded', reason=row['exclude_reason'])
        else: out['reason'] = '排除计价需要填写原因'
        return out
    try:
        if not row.get('name', '').strip(): raise RuleError('请填写设备名称')
        if row.get('source_issue') and not row.get('source_issue_confirmed'): raise RuleError('请核对原文件未缓存公式或错误值')
        if not catalog: raise RuleError('设备指标尚未发布或存在多个有效指标，请先核对指标版本')
        qty = positive(row, 'quantity', '数量')
        kind, a = classify(row), parse_attributes(row)
        if kind not in KINDS: raise RuleError('未识别设备类型，请明确本行产品类型')
        if kind != 'elbow' and row.get('unit') not in {'台', '个', '只', '套', '件', '组', '樘'}:
            raise RuleError('设备数量单位应为台、个、只、套、件、组或樘')
        rates = {k: number(v) for k, v in catalog['rates'].items()}
        term = next((x for x in catalog['payment_terms'] if x['code'] == options.get('payment_option') and x['mode'] == 'no_install'), None)
        if not term: raise RuleError('请选择设备供货付款方式')
        if options.get('tax_included') is not True and options.get('tax_included') is not False: raise RuleError('请明确设备报价是否含税')
        if options.get('include_installation'): raise RuleError('设备安装费暂未纳入计算')
        if options.get('include_freight') is not True and options.get('include_freight') is not False:
            raise RuleError('请明确设备是否包含运费')
        detail = {}
        if kind == 'fan':
            content = ' '.join(str(row.get(k,'')) for k in ('name','spec','remark'))
            if re.search(r'(?:带|附)[^；;\n]*(?:防火阀|止回阀)', content):
                raise RuleError('风机含附带阀门，请明确整套供货配置及成本；不能只报价普通风机')
            cost, detail = select_fan(row, catalog, a)
        elif kind in catalog['valves'] or kind == 'pressure_relief': cost, detail = valve_cost(kind, a, catalog)
        elif kind in {'louver', 'silencer', 'plenum'}: cost, detail = area_cost(kind, row, a, catalog)
        elif kind == 'purchased': cost, detail = purchased_cost(row, a, catalog)
        else: cost = None
        if cost is not None:
            if cost < 0: raise RuleError('设备成本不能为负')
            if kind == 'fan' and enabled(a.get('explosion')):
                tier = next(x for x in catalog['explosion_tiers'] if x['limit'] is None or cost <= number(x['limit']))
                detail.update(ordinary_cost=text_number(cost), explosion_factor=tier['factor'])
                cost *= number(tier['factor'])
            out['unit_cost'] = text_number(cost)
            base = Fraction(cost)/Fraction(1-rates['gross_margin'])
            out['trace'].append(dict(step='不含税成本', expression='本体、机构、电机、面板等按本行适用规则合计', value=text_number(cost)))
            out['trace'].append(dict(step='不含税供货价', expression='成本 ÷ (1 − 毛利率)', value=text_number(base)))
            if options['tax_included']: base *= Fraction(rates['tax_factor'])
        else:
            if row.get('unit') in {'㎡', 'm2', 'm²', '平方米'}: area = Decimal(1)
            elif row.get('unit') in {'台', '个', '只', '件', '组', '套'}: area = positive(a, 'area_per_unit', '每个消声弯头人工计算面积/方量')
            else: raise RuleError('消声弯头请明确按个或㎡计价，避免面积重复乘数量')
            base = Fraction(area*rates['elbow_taxed_rate'])
            detail.update(area_per_unit=text_number(area), fixed_taxed_base=text_number(base))
            if not options['tax_included']: base /= Fraction(rates['tax_factor'])
            out['trace'].append(dict(step='消声弯头固定供货价', expression='人工面积 × 200（含税）；不含税报价除税系数', value=text_number(base)))
        if a.get('shape') == 'round' and kind in set(catalog['valves']) | {'pressure_relief', 'louver', 'silencer', 'plenum'}:
            base *= Fraction(rates['round_factor']); detail['round_factor'] = text_number(rates['round_factor'])
        base *= Fraction(number(term['coefficient']))
        threshold_amount = base*Fraction(qty)
        out['supply_unit_unrounded'] = text_number(base)
        out['supply_amount_unrounded'] = text_number(threshold_amount)
        freight = Fraction(0)
        if options.get('include_freight'):
            region = str(options.get('region', '')).removesuffix('省').removesuffix('壮族自治区')
            if region not in catalog['regions']: raise RuleError('含运费报价仅适用贵州、四川、广西、云南、陕西、海南，请明确目的省区')
            if threshold_amount < rates['freight_threshold']:
                if a.get('freight_per_unit') in (None, ''): raise RuleError('本行未加运费供货金额低于8000元，请填写每台/每个运费（可填0）')
                freight = Fraction(number(a['freight_per_unit']))
                if freight < 0: raise RuleError('每台运费不能为负')
                detail['freight_basis'] = 'manual_per_unit'
            else:
                freight = base*(Fraction(number(catalog['regions'][region]))-1)
                detail.update(freight_basis='regional', freight_factor=catalog['regions'][region])
        unit = ceil(base+freight)
        amount = ceil(unit*qty)
        out['trace'] += [dict(step='付款及税后供货价', expression='供货价 × 付款系数（已按本单税口径）', value=text_number(base)),
                         dict(step='每单位运费', expression='按本行未加运费供货金额判断8000元门槛', value=text_number(freight)),
                         dict(step='最终单价', expression='CEILING(供货单价 + 每单位运费)', value=text_number(unit)),
                         dict(step='行金额', expression='CEILING(最终单价 × 数量)', value=text_number(amount))]
        out.update(status='priced', unit_price=text_number(unit), amount=text_number(amount), body_amount=text_number(amount),
                   billing_quantity=text_number(qty), billing_unit=row['unit'], detail=detail, attributes=a,
                   equipment_kind=kind, payment=term, freight_per_unit=text_number(freight),
                   coverage={'installation': False, 'freight': bool(options.get('include_freight'))},
                   tax_basis='included' if options['tax_included'] else 'excluded',
                   rule={**{k: resource.get(k) for k in ('id','kind','family','version')},
                         'body': {k: resource['body'].get(k) for k in ('name','source','effective_from')}})
    except FanSelectionError as error:
        out.update(reason=str(error), candidates=[{'id': p['id'], 'body': {'name': p['model'], 'attributes': p}} for p in error.candidates])
    except (RuleError, KeyError, TypeError, ValueError, ArithmeticError) as error:
        out['reason'] = str(error) if isinstance(error, RuleError) else '设备规则或数据结构不完整，请核对指标'
    return out


def calculate_equipment(quote, resources, policy):
    active = [x for x in active_catalog(resources) if x['kind'] == 'equipment_catalog']
    catalog, resource = None, None
    issues = []
    if len(active) == 1:
        resource = active[0]
        try: validate_catalog(resource['body']); catalog = resource['body']
        except (RuleError, TypeError, KeyError) as e: issues.append('设备指标无效：'+str(e))
    else: issues.append('需要一个已发布且生效的设备指标版本')
    options = quote.get('equipment_options', {})
    with localcontext() as ctx:
        ctx.prec = 48
        rows = [calculate_row(row, catalog, options, resource) for row in quote.get('rows', [])]
        subtotal = sum((number(r['amount']) for r in rows if r['status'] == 'priced'), Decimal(0))
    pending = sum(r['status'] == 'pending' for r in rows)
    if pending: issues.append(f'有{pending}项设备待确认，未计入小计')
    if not any(r['status'] == 'priced' for r in rows): issues.append('没有已计算的设备项目')
    term = next((x for x in (catalog or {}).get('payment_terms', []) if x['code'] == options.get('payment_option')), None)
    total = text_number(subtotal) if not issues else None
    if not policy.get('formal_template_confirmed'): issues.append('正式客户模板尚未确认')
    return dict(rows=rows, subtotal=text_number(subtotal), total=total, pending_count=pending, issues=issues,
                formal_ready=not issues, fees={}, policy=policy, payment=term, accessory_items=[],
                rounding_difference='0', engine_version=EQUIPMENT_ENGINE_VERSION)


def calculate_mixed(quote, resources, policy):
    from .duct import calculate_duct, DUCT_ENGINE_VERSION
    duct_indices = [i for i, row in enumerate(quote.get('rows', [])) if row.get('category') == '风管']
    duct_set = set(duct_indices)
    equipment_indices = [i for i in range(len(quote.get('rows', []))) if i not in duct_set]
    rows = [None]*len(quote.get('rows', [])); groups = {}
    for label, indices, fn in [('duct', duct_indices, calculate_duct), ('equipment', equipment_indices, calculate_equipment)]:
        if not indices: continue
        if all(quote['rows'][i].get('excluded') and quote['rows'][i].get('exclude_reason','').strip() for i in indices):
            for i in indices: rows[i] = dict(status='excluded', reason=quote['rows'][i]['exclude_reason'], unit_price=None, amount=None, trace=[], candidates=[])
            continue
        group = fn({**quote, 'rows': [quote['rows'][i] for i in indices]}, resources, policy)
        for i, result in zip(indices, group['rows']): rows[i] = result
        groups[label] = group
    issues = [issue for g in groups.values() for issue in g['issues']]
    if not groups: issues.append('清单没有需要计价的产品行')
    dopt, eopt = quote.get('duct_options', {}), quote.get('equipment_options', {})
    if 'duct' in groups and 'equipment' in groups:
        if dopt.get('tax_included') != eopt.get('tax_included'): issues.append('复合报价的风管和设备含税口径需保持一致')
        if dopt.get('payment_option') != eopt.get('payment_option'): issues.append('复合报价的风管和设备付款方式需保持一致')
        if dopt.get('include_installation'): issues.append('本次复合报价暂不包含安装')
    subtotal = sum((number(g['subtotal']) for g in groups.values()), Decimal(0))
    total = None if issues else text_number(sum((number(g['total']) for g in groups.values()), Decimal(0)))
    duct = groups.get('duct', {})
    return dict(rows=rows, subtotal=text_number(subtotal), total=total,
                pending_count=sum(g['pending_count'] for g in groups.values()), issues=issues,
                formal_ready=not issues, fees=duct.get('fees', {}), policy=policy,
                payment=groups.get('equipment', duct).get('payment'), accessory_items=duct.get('accessory_items', []),
                aggregate_flanges=duct.get('aggregate_flanges', []),
                duct_result=duct, engine_version=MIXED_ENGINE_VERSION, duct_engine_version=DUCT_ENGINE_VERSION)
