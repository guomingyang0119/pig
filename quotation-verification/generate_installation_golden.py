# 安装迁移阶段离线生成器，使用冻结 installation-reference 重放旧风管/设备输入并补充安装边界。
# 执行会覆盖三个压缩基线文件，不修改参考源码，也不在 Java 应用运行时调用。
"""使用 2026-09-24 冻结 Python 参考实现，重放已有输入和安装边界用例。"""
import copy, gzip, itertools, json, sys
from pathlib import Path
# 相对脚本解析 pig 项目根目录，引用专用安装参考副本和测试夹具。
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'quotation-verification/installation-reference'))
sys.path.insert(0, str(ROOT / 'quotation-verification/installation-reference/tests'))
from quotation.engine import calculate
from quotation.installation import calculate_installation
from test_installation import install_resource, equipment_resource, install_quote, enable, duct_catalog, duct_quote
# 压缩结果保存到 Java 测试资源，历史原基线保留用于输入重放。
OUT = ROOT / 'pig-upms/pig-upms-biz/src/test/resources/quotation'
# 以 UTF-8 压缩 JSON 保存当前生成数据，保留中文字段。
def write(name, data):
    with gzip.open(OUT / name, 'wt', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
# 风管按原期望结构递归保留同一字段集合；列表按模板项目形状投影。
def project(template, result):
    if isinstance(template, dict): return {k:project(v,result.get(k)) for k,v in template.items()}
    if isinstance(template, list) and template and result:
        return [project(template[min(i,len(template)-1)],v) for i,v in enumerate(result)]
    return result
# 重放旧两类输入并补齐统一安装、付款及取整字段，生成安装接入后的新基线文件。
for mode in ('duct','equipment'):
    with gzip.open(OUT / f'{mode}-golden.json.gz', 'rt', encoding='utf-8') as f: fixture=json.load(f)
    for case in fixture['cases']:
        resources=fixture['catalogs'][case['resources_key']] if mode=='equipment' else case.get('resources',fixture['resources'])
        result=calculate(case['quote'],resources,case.get('policy',fixture.get('policy',{})))
        case['expected']=result if mode=='equipment' else project(case['expected'], result)
        for key in ('before_payment_total','payment_amount','rounding_difference','installation_engine_version'):
            case['expected'][key]=result[key]
        case['expected']['fees']['installation']=result['fees']['installation']
    write(f'{mode}-installation-v1-golden.json.gz', fixture)
    print(mode,len(fixture['cases']))
cases=[]
# fee 模式只计算安装费用，order 模式计算整单；深拷贝输入和资源避免后续矩阵变更污染旧用例。
def add(name,q,resources=None,calculated=None,mode='fee'):
    rs=resources if resources is not None else [install_resource()]
    cr=calculated or [{} for _ in q['rows']]
    if mode=='fee':
        fee,issues=calculate_installation(q,rs,cr); expected={'fee':fee,'issues':issues}
    else:
        r=calculate(q,rs,{'formal_template_confirmed':True})
        expected={k:r.get(k) for k in ('subtotal','total','formal_ready','before_payment_total','payment_amount','rounding_difference','installation_engine_version')}
        expected['installation']=r['fees']['installation']
    cases.append(dict(name=name,quote=copy.deepcopy(q),resources=copy.deepcopy(rs),calculated_rows=cr,mode=mode,expected=expected))
# 逐项目覆盖所有适用地区、是否辅材和小数/整数工程量，按原项目单位计费。
for p in install_resource()['body']['projects']:
    for region,aux,qty in itertools.product(p['regions'],[False,True],['0.11','2']):
        q=install_quote([dict(name=p['name'],category='风管' if p['kind']=='duct' else '',unit=p['unit'],quantity=qty,engineering_quantity=qty,attributes={},installation={'project_id':p['id'],'power':'1.5'})])
        q['installation_options'].update(region=region,include_auxiliaries=aux)
        add(f"project-{p['id']}-{region}-{aux}-{qty}",q)
# 风机功率覆盖 1.5/17.5kW 两端边界、双速、空值、非法值及超范围值。
for region,power in itertools.product(['陕西','陕西省'],['','0','-1','1.49','1.5','17.5','17.51','18.5/15','abc','1e19']):
    q=install_quote([dict(name='排烟风机',unit='台',quantity='2',attributes={'power':power})]);q['installation_options']['region']=region
    add(f'fan-{region}-{power}',q)
# 自动项目匹配分别测四地区、陕西、没有基价地区及未选地区，歧义保持待确认。
for name in ['防火阀','电动调节阀','防雨百叶风口','远程控制机构','多叶送风口','不燃风管','壁式风机','换气扇','自垂百叶','静压箱','风管防火包裹','共板法兰风管','角钢法兰风管','不锈钢风管','螺旋风管']:
    for region in ['贵州','陕西','海南','']:
        q=install_quote([dict(name=name,unit='个',quantity='2',engineering_quantity='3',attributes={})]);q['installation_options']['region']=region
        add(f'auto-{name}-{region}',q)
# 成品/半成品和人工单价空值、零、负数、正常值分别验证，辅材未核实不能自动借价。
for region,state,price,aux in itertools.product(['贵州','陕西','海南',''],['semi','finished'],['','0','-1','7.21'],[False,True]):
    q=install_quote();q['duct_options']={'product_state':state,'area_basis':'net'}
    q['installation_options'].update(region=region,include_auxiliaries=aux)
    q['rows'][0]['installation']={'unit_price':price,'auxiliary_price':'2.12'}
    add(f'manual-{region}-{state}-{price}-{aux}',q)
# 施工标高、交叉施工、费用选择和零/小数/负现场费按该冻结阶段规则生成。
for region,height,cross,selected,site in itertools.product(['贵州','陕西'],['0','4','4.01','5','5.01','-1'],[False,True],[False,True],['','0','0.03','-1']):
    q=install_quote();q['installation_options'].update(region=region,height=height,cross_work=cross,include_site_fee=selected,site_amount=site)
    q['rows'][0]['installation']={'unit_price':'30'};add(f'site-{region}-{height}-{cross}-{selected}-{site}',q)
# 供货个数与安装项目单位不一致时，必须明确安装工程量，空值及零量不能默认为有效。
for unit,qty in itertools.product(['','个','套','㎡'],['','0','2']):
    q=install_quote([dict(name='防火阀',unit='个',quantity='10',installation={'project_id':'sx_23','unit':unit,'quantity':qty})]);q['installation_options']['region']='陕西'
    add(f'units-{unit}-{qty}',q)
# 独立防火包裹项目只增加安装费用；产品排除安装时需要填写原因。
q=install_quote();q['installation_items']=[dict(name='风管防火包裹',project_id='four_11',quantity='2',unit='㎡')];q['installation_options']['include_auxiliaries']=True;add('independent-wrap',q)
for excluded,reason in itertools.product([True,False],['','本项不安装']):
    q=install_quote();q['rows'][0]['installation']={'excluded':excluded,'exclude_reason':reason};add(f'exclusion-{excluded}-{reason}',q)
# 新草稿不取代正式价，最新停用/过期版本不回退；另覆盖无指标和旧整单手填安装金额。
for status in ['draft','retired','published']:
    r=install_resource();r.update(version=2,status=status);r['body']['effective_to']='2020-01-01';add('tombstone-'+status,install_quote(),[install_resource(),r])
add('missing-catalog',install_quote(),[])
q=install_quote();q.pop('installation_options');q['duct_options']={'include_installation':True,'installation_amount':'1000'};add('legacy-installation',q)
# 纯风管、纯设备及混合整单都验证含税选择与含安装付款系数，避免子引擎重复应用付款。
for mode,tax,term in itertools.product(['duct_v1','equipment_v1','mixed_v1'],[False,True],['with_install_cash','with_install_progress_80']):
    q=enable(duct_quote(),payment=term);q['pricing_mode']=mode;q['duct_options']['tax_included']=tax
    q['rows'][0].update(engineering_quantity='2.5',installation={'unit_price':'10'})
    q['equipment_options']=dict(payment_option=term,tax_included=tax,include_freight=False)
    er=dict(name='测试设备',category='外购设备',quantity='2',unit='台',attributes={'model':'TEST'},installation={'unit_price':'7'})
    if mode=='equipment_v1':q['rows']=[er]
    elif mode=='mixed_v1':q['rows'].append(er)
    add(f'order-{mode}-{tax}-{term}',q,[duct_catalog(),equipment_resource(),install_resource()],mode='order')
# 输出新增安装用例基线；冻结价格和待确认提示属于该参考实现阶段。
write('installation-golden.json.gz', {'cases':cases})
print('installation',len(cases))
