"""Fan selection uses performance records first, then verifies body and motor costs."""
import re

from .engine import RuleError, evaluate, number, text_number
from .equipment_sources import powers


def normalize_model(value):
    s = str(value or '').upper().replace('（', '(').replace('）', ')').replace('一', '-').replace('－', '-')
    s = re.sub(r'\s+', '', s).replace('Ⅰ', 'I').replace('Ⅱ', 'II').replace('Ⅲ', 'III')
    s = re.sub(r'^HTF-(?:II|I)(?=-|$)', 'HTF(A)', s)
    s = re.sub(r'^SWF-I(?=-|$)', 'SWF', s)
    return s


def family_of(model):
    for family in ('HTFC', 'HTF(A)', 'SWF'):
        if model.startswith(family): return family
    return None


def material_price(catalog, material, thickness, galvanized):
    material = str(material or '').lower()
    if not material or material in {'galvanized', 'galvanized_40', 'galvanized_80', '镀锌', '镀锌板', '钢板', '普通'}:
        return number(galvanized)
    grade = '304' if '304' in material else '201' if '201' in material else None
    if not grade: raise RuleError('请明确材料为镀锌、201或304不锈钢')
    if thickness in (None, ''): raise RuleError('不锈钢成本查价需要对应板厚')
    matches = [x for x in catalog['materials'] if x['material'] == grade and number(x['thickness']) == number(thickness)]
    if len(matches) != 1: raise RuleError(f'{grade}不锈钢{thickness}mm吨价缺失或重复')
    return number(matches[0]['ton_price'])


def fan_cost(point, catalog, attrs):
    bodies = [x for x in catalog['fan_bodies'] if x['family'] == point['family'] and number(x['size']) == number(point['size'])]
    if len(bodies) != 1: raise RuleError('选定机号的本体成本公式缺失或重复')
    body = bodies[0]
    selected_powers = sorted(map(number, point['powers']), reverse=True)
    selected_poles = sorted(map(number, point.get('poles', [])))
    matches = [m for m in catalog['motors']
               if sorted(map(number, m['powers']), reverse=True) == selected_powers
               and sorted(map(number, m['poles'])) == selected_poles]
    if attrs.get('motor_model'):
        matches = [m for m in matches if normalize_model(m['model']) == normalize_model(attrs['motor_model'])]
    if not matches: raise RuleError('所选功率及极数组合缺少电机价')
    unique = {(m['model'], m['cost']) for m in matches}
    if len(unique) != 1: raise RuleError('同功率极数对应多个电机，请明确电机型号')
    motor = matches[0]
    thickness = attrs.get('thickness') or body.get('thickness')
    env = {'size': number(body['size']), 'thickness': number(thickness) if thickness else 1,
           'steel_price': material_price(catalog, attrs.get('material'), thickness, body['steel_price'])}
    material = evaluate(body['material_expression'], env)
    other = evaluate(body['other_expression'], env)
    if material < 0 or other < 0: raise RuleError('风机本体成本公式结果不能为负')
    return material + other + number(motor['cost']), {
        'body': body, 'motor': motor, 'material_cost': text_number(material),
        'body_other_cost': text_number(other), 'motor_cost': motor['cost'],
        'steel_price': text_number(env['steel_price'])}


class FanSelectionError(RuleError):
    def __init__(self, message, candidates=()):
        super().__init__(message)
        self.candidates = list(candidates)[:50]


def select_fan(row, catalog, attrs):
    requested = normalize_model(attrs.get('model'))
    family = family_of(requested)
    requested_powers = powers(attrs['power']) if attrs.get('power') else []
    if any(number(p) <= 0 for p in requested_powers): raise RuleError('需求功率必须大于0')
    # A quotation remark can explicitly specify the actual higher motor configuration.
    override = re.search(r'(\d+(?:\.\d+)?)\s*(?:KW|千瓦)\s*单价', str(row.get('remark', '')).upper())
    if override: requested_powers = powers(override[1])
    full_text = ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark')) + ' ' + requested
    raw_model = str(attrs.get('raw_model') or attrs.get('model', '')).upper().replace('Ⅱ', 'II')
    double = (len(requested_powers) > 1 or '双速' in full_text or bool(re.search(r'(?:HTF|SWF)-II(?:-|$)', raw_model)))
    if attrs.get('dual_speed') not in (None, ''): double = str(attrs['dual_speed']).lower() in {'true', '1', '是', '双速'}
    if requested.startswith('HTFC-') and re.search(r'-[12]$', requested): double = True
    flow = number(attrs['airflow']) if attrs.get('airflow') else None
    pressure = number(attrs['pressure']) if attrs.get('pressure') else None
    low_requirements = {k: number(attrs[k]) for k in ('airflow_low','pressure_low') if attrs.get(k)}
    if any(x is not None and x <= 0 for x in (flow, pressure)): raise RuleError('风量、风压必须大于0')
    if not requested and not requested_powers and (flow is None or pressure is None):
        raise FanSelectionError('无型号、功率时需同时提供风量和风压')
    if not requested and requested_powers and (flow is None or pressure is None) and not attrs.get('size'):
        raise FanSelectionError('仅功率不能确定风机，请补型号或风量、风压')
    if requested and family is None and (flow is None or pressure is None):
        raise FanSelectionError('其他系列重选科庆型号需要风量和风压')
    candidates = []
    requested_group = requested.rsplit('-', 1)[-1] if double and requested.startswith('HTFC-') and requested.rsplit('-', 1)[-1] in {'1', '2'} else None
    for p in catalog['fan_points']:
        if p.get('quarantined'): continue
        if family and p['family'] != family: continue
        if (len(p['powers']) > 1) != double: continue
        if requested_group and p.get('group') != requested_group: continue
        if flow is not None and number(p['airflow']) < flow: continue
        if pressure is not None and number(p['pressure']) < pressure: continue
        if any(not p.get(k) or number(p[k]) < value for k, value in low_requirements.items()): continue
        if attrs.get('speed') and number(p['speed']) != number(attrs['speed']): continue
        pp = sorted(map(number, p['powers']), reverse=True)
        if requested_powers and pp[0] < number(requested_powers[0]): continue
        if len(requested_powers) > 1 and (len(pp) != len(requested_powers) or any(a < number(b) for a, b in zip(pp, requested_powers))): continue
        candidates.append(p)
    if not candidates:
        reason = '需核实双速高低档风量/风压配对数据' if low_requirements else '没有满足风量、风压、功率及单双速要求的科庆参数，请核实需求'
        raise FanSelectionError(reason)
    eligible = []
    for p in candidates:
        try:
            cost, detail = fan_cost(p, catalog, attrs)
            eligible.append((p, cost, detail))
        except RuleError:
            continue
    if not eligible: raise FanSelectionError('符合性能的候选均缺少有效本体或电机成本，需补齐价格', candidates)
    preferred = [x for x in eligible if normalize_model(x[0]['model']) == requested]
    substituted = bool(requested and not preferred)
    if preferred:
        eligible = preferred
    elif attrs.get('size') and not requested:
        sized = [x for x in eligible if number(x[0]['size']) == number(attrs['size'])]
        if sized: eligible = sized
    if substituted and (flow is None or pressure is None):
        raise FanSelectionError('原型号缺价或性能不满足；重选需要风量、风压', [x[0] for x in eligible])
    if requested_powers:
        lowest = min(number(x[0]['powers'][0]) for x in eligible)
        eligible = [x for x in eligible if number(x[0]['powers'][0]) == lowest]
    chosen_id = row.get('candidate_id')
    if chosen_id:
        chosen = [x for x in eligible if x[0]['id'] == chosen_id]
        if not chosen: raise FanSelectionError('指定候选已不满足当前条件或成本资料，请重新选择', [x[0] for x in eligible])
        selected = chosen[0]
    else:
        # Without performance, never choose one of several operating points silently.
        if flow is None and pressure is None and len(eligible) > 1:
            raise FanSelectionError('仍有多个工况点，请补风量/风压或明确选择候选', [x[0] for x in eligible])
        backup = not requested and not requested_powers
        reselect = substituted or (not preferred and flow is not None and pressure is not None)
        if not backup and not reselect and len(eligible) > 1:
            exact = [x for x in eligible if (flow is None or number(x[0]['airflow']) == flow) and
                     (pressure is None or number(x[0]['pressure']) == pressure)]
            if exact: eligible = exact
            if len(eligible) > 1:
                raise FanSelectionError('主路径仍有多个合格工况点，请明确候选型号/功率/转速配置', [x[0] for x in eligible])
        if backup or reselect:
            smallest = min(number(x[0]['size']) for x in eligible)
            eligible = [x for x in eligible if number(x[0]['size']) == smallest]
        def score(entry):
            p = entry[0]
            return (number(p['powers'][0]), number(p['airflow']), number(p['pressure']), number(p['speed']))
        eligible.sort(key=score)
        best = [x for x in eligible if score(x) == score(eligible[0])]
        if len({(x[0]['model'], tuple(x[0]['poles'])) for x in best}) > 1:
            raise FanSelectionError('多个系列满足相同工况，请明确候选型号', [x[0] for x in best])
        selected = eligible[0]
    point, cost, detail = selected
    warning = '需求机号与型号不一致，按型号选型' if attrs.get('size') and preferred and number(attrs['size']) != number(point['size']) else ''
    return cost, {'selected_fan': point, 'fan_cost': detail, 'selection_note': warning or ('按风量、风压选择有完整成本的最小合格规格' if substituted or not requested else ''),
                  'requested_model': attrs.get('raw_model') or attrs.get('model', ''),
                  'requested_power': attrs.get('power', ''),
                  'power_override': override[1] if override else None,
                  'substituted': substituted, 'selection_path': 'main' if requested or requested_powers else 'performance'}
