"""Replay prior inputs and installation boundaries against the frozen 20260924 Python."""
import copy, gzip, itertools, json, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'quotation-verification/installation-reference'))
sys.path.insert(0, str(ROOT / 'quotation-verification/installation-reference/tests'))
from quotation.engine import calculate
from quotation.installation import calculate_installation
from test_installation import install_resource, equipment_resource, install_quote, enable, duct_catalog, duct_quote
OUT = ROOT / 'pig-upms/pig-upms-biz/src/test/resources/quotation'
def write(name, data):
    with gzip.open(OUT / name, 'wt', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
def project(template, result):
    if isinstance(template, dict): return {k:project(v,result.get(k)) for k,v in template.items()}
    if isinstance(template, list) and template and result:
        return [project(template[min(i,len(template)-1)],v) for i,v in enumerate(result)]
    return result
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
for p in install_resource()['body']['projects']:
    for region,aux,qty in itertools.product(p['regions'],[False,True],['0.11','2']):
        q=install_quote([dict(name=p['name'],category='风管' if p['kind']=='duct' else '',unit=p['unit'],quantity=qty,engineering_quantity=qty,attributes={},installation={'project_id':p['id'],'power':'1.5'})])
        q['installation_options'].update(region=region,include_auxiliaries=aux)
        add(f"project-{p['id']}-{region}-{aux}-{qty}",q)
for region,power in itertools.product(['陕西','陕西省'],['','0','-1','1.49','1.5','17.5','17.51','18.5/15','abc','1e19']):
    q=install_quote([dict(name='排烟风机',unit='台',quantity='2',attributes={'power':power})]);q['installation_options']['region']=region
    add(f'fan-{region}-{power}',q)
for name in ['防火阀','电动调节阀','防雨百叶风口','远程控制机构','多叶送风口','不燃风管','壁式风机','换气扇','自垂百叶','静压箱','风管防火包裹','共板法兰风管','角钢法兰风管','不锈钢风管','螺旋风管']:
    for region in ['贵州','陕西','海南','']:
        q=install_quote([dict(name=name,unit='个',quantity='2',engineering_quantity='3',attributes={})]);q['installation_options']['region']=region
        add(f'auto-{name}-{region}',q)
for region,state,price,aux in itertools.product(['贵州','陕西','海南',''],['semi','finished'],['','0','-1','7.21'],[False,True]):
    q=install_quote();q['duct_options']={'product_state':state,'area_basis':'net'}
    q['installation_options'].update(region=region,include_auxiliaries=aux)
    q['rows'][0]['installation']={'unit_price':price,'auxiliary_price':'2.12'}
    add(f'manual-{region}-{state}-{price}-{aux}',q)
for region,height,cross,selected,site in itertools.product(['贵州','陕西'],['0','4','4.01','5','5.01','-1'],[False,True],[False,True],['','0','0.03','-1']):
    q=install_quote();q['installation_options'].update(region=region,height=height,cross_work=cross,include_site_fee=selected,site_amount=site)
    q['rows'][0]['installation']={'unit_price':'30'};add(f'site-{region}-{height}-{cross}-{selected}-{site}',q)
for unit,qty in itertools.product(['','个','套','㎡'],['','0','2']):
    q=install_quote([dict(name='防火阀',unit='个',quantity='10',installation={'project_id':'sx_23','unit':unit,'quantity':qty})]);q['installation_options']['region']='陕西'
    add(f'units-{unit}-{qty}',q)
q=install_quote();q['installation_items']=[dict(name='风管防火包裹',project_id='four_11',quantity='2',unit='㎡')];q['installation_options']['include_auxiliaries']=True;add('independent-wrap',q)
for excluded,reason in itertools.product([True,False],['','本项不安装']):
    q=install_quote();q['rows'][0]['installation']={'excluded':excluded,'exclude_reason':reason};add(f'exclusion-{excluded}-{reason}',q)
for status in ['draft','retired','published']:
    r=install_resource();r.update(version=2,status=status);r['body']['effective_to']='2020-01-01';add('tombstone-'+status,install_quote(),[install_resource(),r])
add('missing-catalog',install_quote(),[])
q=install_quote();q.pop('installation_options');q['duct_options']={'include_installation':True,'installation_amount':'1000'};add('legacy-installation',q)
for mode,tax,term in itertools.product(['duct_v1','equipment_v1','mixed_v1'],[False,True],['with_install_cash','with_install_progress_80']):
    q=enable(duct_quote(),payment=term);q['pricing_mode']=mode;q['duct_options']['tax_included']=tax
    q['rows'][0].update(engineering_quantity='2.5',installation={'unit_price':'10'})
    q['equipment_options']=dict(payment_option=term,tax_included=tax,include_freight=False)
    er=dict(name='测试设备',category='外购设备',quantity='2',unit='台',attributes={'model':'TEST'},installation={'unit_price':'7'})
    if mode=='equipment_v1':q['rows']=[er]
    elif mode=='mixed_v1':q['rows'].append(er)
    add(f'order-{mode}-{tax}-{term}',q,[duct_catalog(),equipment_resource(),install_resource()],mode='order')
write('installation-golden.json.gz', {'cases':cases})
print('installation',len(cases))
