"""Installation quantities and sales prices, separate from material area factors."""
import re
from decimal import Decimal, ROUND_CEILING, localcontext

from .engine import RuleError, active_catalog, number, text_number
from .installation_catalog import FOUR_REGIONS, validate_catalog

INSTALLATION_ENGINE_VERSION = 'installation-v4'
UNITS = {'m2': '㎡', 'm²': '㎡', '平方米': '㎡', '平米': '㎡'}


def ceil_yuan(value):
    return value.to_integral_value(rounding=ROUND_CEILING)


def installation_enabled(quote):
    if 'installation_options' in quote:
        return quote['installation_options'].get('included') is True
    return any(quote.get(key, {}).get('include_installation') is True
               for key in ('duct_options', 'equipment_options'))


def installation_tax_included(quote):
    mode = quote.get('pricing_mode', '')
    equipment = quote.get('equipment_options', {})
    duct = quote.get('duct_options', {})
    if mode in ('equipment_v1', 'mixed_v1') and 'tax_included' in equipment:
        return equipment.get('tax_included') is True
    if 'tax_included' in duct:
        return duct.get('tax_included') is True
    return equipment.get('tax_included') is True


def region_name(value):
    return str(value or '').strip().removesuffix('省').removesuffix('壮族自治区')


def unit_name(value):
    return UNITS.get(str(value or '').strip(), str(value or '').strip())


def nonnegative(value, label, positive=False):
    if value in (None, ''):
        raise RuleError('请填写' + label)
    val = number(value)
    if val < 0 or (positive and val == 0):
        raise RuleError(label + ('必须大于0' if positive else '不能为负'))
    return val


def duct_row(row):
    return row.get('category') == '风管' or ('风管' in row.get('name', '') and row.get('category') in ('', None))


def product_state(row, quote, entry):
    if row.get('attributes', {}).get('product_state'):
        return row['attributes']['product_state']
    text = ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark'))
    if '半成品' in text:
        return 'semi'
    if '成品' in text:
        return 'finished'
    if entry.get('product_state'):
        return entry['product_state']
    return quote.get('duct_options', {}).get('product_state') or 'semi'


def explicit_remote_control(row):
    return any(word in ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark'))
               for word in ('远控', '远程控制'))


def fan_project(row, shaanxi):
    text = ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark'))
    name = row.get('name', '')
    if '壁式' in name:
        return 'sx_22' if shaanxi and '方形' in name else 'four_14' if not shaanxi else None
    if any(word in name for word in ('油烟净化', '管道风机', '管道式排风机')):
        return 'sx_20' if shaanxi else 'four_13'
    return 'sx_19' if shaanxi and any(word in text for word in ('消防', '排烟', '送风', '排风')) else 'four_13' if not shaanxi else None


def auto_project(row, quote, region):
    """Only distinctive names/conditions match automatically; never pick a nearest price."""
    text = ' '.join(str(row.get(k, '')) for k in ('name', 'spec', 'remark'))
    name = row.get('name', '')
    sx = region == '陕西'
    if region not in FOUR_REGIONS + ['陕西']:
        return None
    prefix = 'sx_' if sx else 'four_'
    if duct_row(row):
        if '包裹' in text or '复合' in text or '彩钢' in text:
            return None
        if '不锈钢' in text or str(row.get('attributes', {}).get('material', '')).startswith('stainless'):
            return prefix + '6' if sx or '满焊' in text else None
        flange = row.get('attributes', {}).get('flange') or quote.get('duct_options', {}).get('flange')
        if '螺旋' in text or '圆形' in text or flange == 'round':
            return 'sx_7' if sx else None
        if '角钢' in text or '角铁' in text:
            flange = 'angle'
        elif '共板' in text:
            flange = 'common'
        if sx:
            if flange == 'angle' and any(w in text for w in ('防排烟', '排烟', '防烟')):
                return 'sx_4'
            if flange == 'common':
                if '卫生间' in text or '空调' in text:
                    return 'sx_5'
                if '正压送风' in text or '地下室送风' in text:
                    return 'sx_3'
        else:
            if '空调' in text:
                return 'four_5' if '小管' in text or '装修' in text else None
            return {'common': 'four_3', 'angle': 'four_4'}.get(flange)
        return None
    if '风机' in name and '百叶' in name:
        return fan_project(row, sx)
    if '防雨百叶' in name:
        if sx:
            return 'sx_33'
        attributes = dict(row.get('attributes', {}))
        match = re.search(r'(\d+(?:\.\d+)?)\s*[×xX*＊]\s*(\d+(?:\.\d+)?)', text)
        if match:
            attributes.setdefault('width', match[1])
            attributes.setdefault('height', match[2])
        if not attributes.get('width') or not attributes.get('height'):
            return None
        width, height = number(attributes['width']), number(attributes['height'])
        if width <= 0 or height <= 0:
            return None
        return 'four_23' if width + height < Decimal('1500') else 'four_24'
    if any(w in name for w in ('消声弯头', '防雨弯头')):
        return 'sx_17' if sx else 'four_26'
    if '散流器' in name:
        return 'sx_14' if sx else 'four_25'
    if sx:
        if '远控常闭排烟阀' in name or ('280' in name and '排烟防火阀' in name):
            return 'sx_24'
        if '电动多叶排烟风口' in name:
            return 'sx_32'
        if any(w in name for w in ('板式排烟口', '远程多叶送风口')):
            return 'sx_33'
        if any(w in name for w in ('多叶送风口', '正压送风口', '电动防火风口')):
            return 'sx_31'
        if '防火风口' in name:
            return 'sx_29' if any(w in name for w in ('70', '弱电', '常开')) else 'sx_26'
        if any(w in name for w in ('成品防火软连接', '柔性软接头')):
            return 'sx_15'
        if any(w in name for w in ('防虫网', '防鼠网')):
            return 'sx_16'
        if '可开链式百叶' in name:
            return 'sx_30'
        if any(w in name for w in ('AH单层格栅风口', 'AH/D单层格栅风口')):
            return 'sx_28'
    else:
        if any(w in name for w in ('板式排烟口', '多页排烟口')):
            return 'four_20'
        if any(w in name for w in ('多叶送风口', '正压送风口', '组合送风口')):
            return 'four_20'
        if '防火风口' in name:
            return 'four_22'
    if any(w in name for w in ('防火阀', '排烟阀', '调节阀', '止回阀', '风阀')):
        if not sx:
            return 'four_16'
        if not any(w in name for w in ('电动', '自动', '远程', '远控')):
            return 'sx_23'
    if any(w in name for w in ('单层百叶', '双层百叶')) or '带阀风口' in name:
        return 'sx_25' if sx else 'four_18'
    if any(w in name for w in ('防雨', '防火风口', '多叶', '远程', '远控', '不燃', '电动', '自动', '带阀')):
        return None
    if any(w in name for w in ('换气扇', '排气扇')):
        return 'sx_21' if sx else 'four_15'
    if '风机' in name:
        return fan_project(row, sx)
    if any(w in name for w in ('静压箱', '消声器')):
        return 'sx_18' if sx else 'four_21'
    if '正压送风口' in name:
        return 'sx_31' if sx else 'four_20'
    if '自垂百叶' in name:
        return 'sx_27' if sx else 'four_19'
    return None


def installation_line(row, entry, quote, catalog, calculated=None, extra=False):
    region = region_name(quote.get('installation_options', {}).get('region'))
    out = dict(name=row.get('name', '') or '安装项目', status='pending', reason='', amount=None)
    try:
        if entry.get('excluded') is True or row.get('excluded') is True:
            reason = entry.get('exclude_reason') or row.get('exclude_reason')
            if not reason:
                raise RuleError('不计安装需要填写原因')
            return {**out, 'status': 'excluded', 'reason': reason}
        if not region:
            raise RuleError('请选择安装地区')
        project_id = entry.get('project_id') or auto_project(row, quote, region)
        project = next((p for p in catalog['projects'] if p['id'] == project_id), None)
        if entry.get('project_id') and not project:
            raise RuleError('安装项目不存在，请重新选择')
        if project and (region not in project['regions'] or project['kind'] == 'auxiliary'):
            raise RuleError('所选安装项目不适用于本地区或属于辅材，请重新选择')
        is_duct = project['kind'] == 'duct' if project else duct_row(row)
        state = product_state(row, quote, entry) if is_duct else 'semi'
        if state not in {'semi', 'finished'}:
            raise RuleError('安装成品状态未识别')
        manual = entry.get('unit_price') not in (None, '')
        if is_duct and state == 'finished' and not manual:
            raise RuleError('成品风管请填写每㎡未税安装销售单价')
        if not project and not manual:
            raise RuleError('未找到唯一安装价，请选择安装项目或手填未税安装单价')
        unit = unit_name(entry.get('unit') or (project or {}).get('unit') or ('㎡' if is_duct else row.get('unit')))
        if unit not in {'㎡', '台', '个', '套'}:
            raise RuleError('请确认安装计量单位（㎡、台、个、套）')
        if project and unit != project['unit']:
            raise RuleError('安装单位与所选项目不一致，不能自动换算')
        if is_duct and state == 'finished' and unit != '㎡':
            raise RuleError('成品风管安装单价必须按每㎡填写')
        quantity = entry.get('quantity')
        if quantity in (None, ''):
            source_unit = '㎡' if duct_row(row) else unit_name(row.get('unit'))
            if unit != source_unit:
                raise RuleError('供货与安装单位不同，请确认安装工程量及单位')
            quantity = row.get('engineering_quantity') if duct_row(row) else row.get('quantity')
        qty = nonnegative(quantity, '安装工程量', positive=True)
        if manual:
            price = nonnegative(entry['unit_price'], '未税安装销售单价')
        elif project.get('power_prices'):
            selected = (calculated or {}).get('detail', {}).get('selected_fan', {})
            powers = selected.get('powers')
            power = entry.get('power') or ('/'.join(map(str, powers)) if powers else row.get('attributes', {}).get('power'))
            if not power:
                match = re.search(r'(\d+(?:\.\d+)?(?:\s*/\s*\d+(?:\.\d+)?)?)\s*(?:kW|千瓦)', row.get('spec', ''), re.I)
                power = match[1] if match else None
            if power and '/' in str(power):
                raise RuleError('双速风机请确认安装计费功率或手填安装单价')
            kw = nonnegative(power, '安装计费功率kW', positive=True)
            band = 0 if kw < Decimal('1.5') else 1 if kw <= Decimal('17.5') else 2
            price = number(project['power_prices'][band])
            out.update(power=text_number(kw), power_band=band)
        else:
            price = number(project['price'])
        base_price, remote_price = price, Decimal(0)
        remote_project = None
        if (not manual and project.get('kind') == 'equipment' and region in FOUR_REGIONS
                and explicit_remote_control(row) and project_id != 'four_17'):
            remote_project = next((p for p in catalog['projects'] if p['id'] == 'four_17' and region in p['regions']), None)
            if not remote_project:
                raise RuleError('远程控制机构安装指标不存在，请先核对安装指标')
            remote_price = number(remote_project['price'])
            price += remote_price
        project_name = project['name'] if project else '人工确认安装价'
        source = '报价员手填未税销售单价' if manual else project['source']
        note = str(entry.get('note') or (project or {}).get('note', '')).strip()
        if remote_project:
            project_name += '＋' + remote_project['name']
            source += '；' + remote_project['source']
            note = (note + '；' if note else '') + '客户清单明确远控，叠加远程控制机构安装费'
        out.update(status='priced', unit=unit, quantity=text_number(qty), unit_price=text_number(price),
                   amount=text_number(qty * price), base_unit_price=text_number(base_price),
                   remote_control_unit_price=text_number(remote_price), project_id=project_id,
                   project_name=project_name, source=source, manual=manual, product_state=state,
                   note=note, auxiliary=None)
        if quote['installation_options'].get('include_auxiliaries') is True and is_duct:
            if unit != '㎡':
                raise RuleError('风管安装辅材必须明确㎡工程量')
            aux_type = entry.get('auxiliary_type') or (project or {}).get('auxiliary_type')
            if region in FOUR_REGIONS:
                aux_price, aux_source = number(catalog['four_auxiliary_price']), '四地表A25'
            elif region == '陕西':
                matches = [p for p in catalog['projects'] if p['kind'] == 'auxiliary' and region in p['regions'] and p.get('auxiliary_type') == aux_type]
                if len(matches) != 1:
                    raise RuleError('请确认陕西风管安装辅材类型（共板、角铁或复合）')
                aux_price, aux_source = number(matches[0]['price']), matches[0]['source']
            else:
                aux_price = nonnegative(entry.get('auxiliary_price'), '本地区人工确认安装辅材单价')
                aux_source = '报价员确认安装辅材单价'
            out['auxiliary'] = dict(name='风管安装辅材', unit='㎡', quantity=text_number(qty),
                                    unit_price=text_number(aux_price), amount=text_number(qty * aux_price), source=aux_source)
    except (RuleError, ArithmeticError, TypeError, ValueError) as error:
        out.update(status='pending', amount=None, auxiliary=None,
                   reason=str(error) if isinstance(error, RuleError) else '安装数据格式不正确，请核对')
    return out


def calculate_installation(quote, resources, calculated_rows):
    empty = dict(amount='0', untaxed_amount='0', status='not_applicable', reason='本单不含安装', lines=[])
    if not installation_enabled(quote):
        return empty, []
    if 'installation_options' not in quote:
        reason = '旧版整单安装金额需核对并改填安装项目，不能直接作为每㎡单价'
        return {**empty, 'amount': None, 'status': 'pending', 'reason': reason}, ['安装费：' + reason]
    active = [r for r in active_catalog(resources) if r['kind'] == 'installation_catalog']
    if len(active) != 1:
        reason = '需要一个已发布且生效的安装指标版本'
        return {**empty, 'amount': None, 'status': 'pending', 'reason': reason}, ['安装费：' + reason]
    resource, options = active[0], quote['installation_options']
    try:
        validate_catalog(resource['body'])
    except (RuleError, TypeError, KeyError) as error:
        return {**empty, 'amount': None, 'status': 'pending', 'reason': str(error)}, ['安装指标无效：' + str(error)]
    catalog = resource['body']
    lines, issues = [], []
    with localcontext() as ctx:
        ctx.prec = 48
        for index, row in enumerate(quote.get('rows', [])):
            calculated = calculated_rows[index] if index < len(calculated_rows) else {}
            line = installation_line(row, row.get('installation', {}), quote, catalog, calculated)
            line['row_index'] = index
            lines.append(line)
        for index, entry in enumerate(quote.get('installation_items', [])):
            row = dict(name=entry.get('name', ''), category=entry.get('category', ''), unit=entry.get('unit', ''), quantity=entry.get('quantity', ''))
            line = installation_line(row, entry, quote, catalog, extra=True)
            line['extra_index'] = index
            lines.append(line)
        for line in lines:
            if line['status'] == 'pending':
                issues.append('安装费：' + line['name'] + '：' + line['reason'])
        priced = [line for line in lines if line['status'] == 'priced']
        if not priced:
            issues.append('安装费：没有已确认的安装项目')
        includes_tax = installation_tax_included(quote)
        tax_factor = Decimal('1.01') if includes_tax else Decimal(1)
        labor = sum((number(line['amount']) for line in priced), Decimal(0))
        auxiliaries = sum((number(line['auxiliary']['amount']) for line in priced if line.get('auxiliary')), Decimal(0))
        line_amount = Decimal(0)
        for line in priced:
            line_untaxed = number(line['amount']) + (number(line['auxiliary']['amount']) if line.get('auxiliary') else Decimal(0))
            line_taxed = line_untaxed * tax_factor
            line_quoted = ceil_yuan(line_taxed)
            line.update(untaxed_total=text_number(line_untaxed), tax_amount=text_number(line_taxed-line_untaxed),
                        rounding_difference=text_number(line_quoted-line_taxed), quoted_amount=text_number(line_quoted))
            line_amount += line_quoted
        site = Decimal(0)
        site_selected = options.get('include_site_fee') is True or ('include_site_fee' not in options and options.get('site_amount') not in (None, ''))
        try:
            if options.get('height') not in (None, ''):
                nonnegative(options['height'], '施工标高')
            if site_selected:
                site = nonnegative(options['site_amount'], '未税现场附加费')
            if site > 0 and not str(options.get('site_note', '')).strip():
                raise RuleError('现场附加费大于0时请填写附加原因')
        except RuleError as error:
            issues.append('安装费：' + str(error))
        site_taxed = site * tax_factor
        site_quoted = ceil_yuan(site_taxed) if includes_tax else site
        untaxed = labor + auxiliaries + site
        taxed = untaxed * tax_factor
        amount = line_amount + site_quoted
        fee = dict(status='pending' if issues else 'ready', amount=None if issues else text_number(amount),
                   untaxed_amount=text_number(untaxed), labor_amount=text_number(labor),
                   auxiliary_amount=text_number(auxiliaries), line_amount=text_number(line_amount),
                   site_amount=text_number(site), site_quoted_amount=text_number(site_quoted),
                   site_tax_amount=text_number(site_taxed-site), site_rounding_difference=text_number(site_quoted-site_taxed),
                   site_note=options.get('site_note', '') if site_selected else '', tax_factor=text_number(tax_factor), tax_amount=text_number(taxed-untaxed),
                   rounding_difference=text_number(amount-taxed), lines=lines,
                   reason=('每行安装人工与辅材分别×1.01后向上取整数元；现场附加费单独×1.01后向上取整数元；付款系数在整单末尾应用'
                           if includes_tax else '每行安装人工与辅材分别向上取整数元；现场附加费按录入金额单独计入；付款系数在整单末尾应用'),
                   catalog={'id': resource['id'], 'version': resource['version'], 'source': catalog['source']},
                   engine_version=INSTALLATION_ENGINE_VERSION)
        return fee, issues


def resolve_equipment_freight(quote, result, installation):
    """Resolve the regional freight trial from the complete order sales amount."""
    freight = result.get('fees', {}).get('equipment_freight', {})
    if freight.get('status') != 'trial':
        return
    term = result.get('payment')
    calculation_issues = [i for i in result['issues'] if not i.startswith('正式客户模板')]
    adjusted = freight.pop('_adjustments', [])
    if not term or calculation_issues:
        reason = '请选择整单付款方式后再判断8000元门槛' if not term else '整单仍有待确认项，无法判断8000元门槛'
        freight.update(status='pending', amount=None, reason=reason, basis='pending', included_in_rows=False)
        result['issues'].append('设备运费：'+reason)
        return
    calculated = number(freight['calculated_amount'])
    trial_before = number(result['subtotal']) + calculated + number(installation['amount'])
    trial_total = ceil_yuan(trial_before * number(term['coefficient']))
    freight.update(trial_order_before_payment_total=text_number(trial_before), trial_order_total=text_number(trial_total))
    if trial_total >= number(freight['threshold']):
        for row, unit, amount, final_unit, final_amount, freight_unit, freight_amount in adjusted:
            row.update(base_unit_price=text_number(unit), base_amount=text_number(amount), unit_price=text_number(final_unit),
                       amount=text_number(final_amount), body_amount=text_number(final_amount), freight_per_unit=text_number(freight_unit),
                       freight_amount=text_number(freight_amount))
            row['coverage']['freight'] = True
            row['detail'].update(freight_basis='regional_per_unit', freight_factor=freight['factor'])
            row['trace'] += [dict(step='含运费单价', expression='CEILING(设备原单价 × 送货省区运费系数)', value=text_number(final_unit)),
                             dict(step='含运费行金额', expression='CEILING(含运费单价 × 数量)', value=text_number(final_amount))]
        result['subtotal'] = text_number(number(result['subtotal']) + calculated)
        freight.update(status='ready', amount=text_number(calculated),
                       reason='整单试算销售额达到8000元，设备运费已逐行计入设备单台售价',
                       basis='regional_per_unit', included_in_rows=True)
        return
    entered = quote.get('equipment_options', {}).get('freight_amount')
    if entered in (None, ''):
        reason = '整单试算销售额低于8000元，请填写整单设备运费（可填0）'
        freight.update(status='pending', amount=None, reason=reason, basis='manual_order', included_in_rows=False)
        result['issues'].append('设备运费：'+reason)
        return
    try:
        manual = number(entered)
        if manual < 0:
            raise RuleError('整单设备运费不能为负')
        freight.update(status='ready', amount=text_number(ceil_yuan(manual)),
                       reason='整单试算销售额低于8000元，采用人工填写的整单设备运费并单独列示',
                       basis='manual_order', included_in_rows=False)
    except (RuleError, TypeError, ValueError):
        reason = '整单设备运费不能为负' if str(entered).strip().startswith('-') else '整单设备运费数据格式不正确，请核对'
        freight.update(status='pending', amount=None, reason=reason, basis='manual_order', included_in_rows=False)
        result['issues'].append('设备运费：'+reason)


def finalize_order(quote, result, resources, supply_only=False):
    """One settlement boundary for duct, equipment and mixed quotes."""
    if supply_only:
        return result
    with localcontext() as ctx:
        ctx.prec = 48
        installation, installation_issues = calculate_installation(quote, resources, result['rows'])
        result.setdefault('fees', {})['installation'] = installation
        result.setdefault('material_total', result['subtotal'])
        result['issues'].extend(installation_issues)
        term = result.get('payment')
        if not term:
            result['issues'].append('请选择整单付款方式')
        resolve_equipment_freight(quote, result, installation)
        calculation_issues = [i for i in result['issues'] if not i.startswith('正式客户模板')]
        total = before = payment_amount = difference = None
        if not calculation_issues:
            freight = result.get('fees', {}).get('equipment_freight', {})
            freight_amount = Decimal(0) if freight.get('included_in_rows') or freight.get('amount') in (None, '') else number(freight['amount'])
            before = number(result['subtotal']) + freight_amount + number(installation['amount'])
            unrounded = before * number(term['coefficient'])
            total = ceil_yuan(unrounded)
            payment_amount, difference = unrounded-before, total-unrounded
        result.update(total=text_number(total) if total is not None else None,
                      before_payment_total=text_number(before) if before is not None else None,
                      payment_amount=text_number(payment_amount) if payment_amount is not None else None,
                      equipment_freight_total=(result.get('fees', {}).get('equipment_freight', {}).get('amount')
                                               if result.get('fees', {}).get('equipment_freight', {}).get('amount') not in (None, '') else None),
                      rounding_difference=text_number(difference) if difference is not None else None,
                      installation_engine_version=INSTALLATION_ENGINE_VERSION,
                      formal_ready=not result['issues'])
        return result
