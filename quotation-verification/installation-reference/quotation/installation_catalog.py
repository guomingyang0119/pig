"""Reviewed installation sales prices; publication uses the normal resource lifecycle."""
from datetime import date

from .engine import RuleError, number

FOUR_REGIONS = ['贵州', '四川', '云南', '广西']
SHEETS = ['贵州、四川、云南、广西风管安装报价', '陕西风管安装报价']
SOURCE_FILE = '科庆-通风劳务安装报价总汇.xlsx   111(1)(1)(1).xlsx'
SOURCE_SHA256 = 'c1066f5f9c3c71072a2be154b96bdc1f5d6ec0d2eb0d2061cc8e818ad1cbe292'

# Row numbers and wording are retained, including ambiguous source descriptions.
FOUR_ROWS = [
    (3, '共板法兰风管', '㎡', '30', 'duct', 'common'),
    (4, '角钢法兰风管', '㎡', '35', 'duct', 'angle'),
    (5, '空调风管（小管及配合装修）', '㎡', '55', 'duct', 'common'),
    (6, '不锈钢风管满焊', '㎡', '100', 'duct', ''),
    (7, '一体式复合风管合管（原表类别为成品吊装）', '㎡', '35', 'duct', 'composite'),
    (8, '单双面彩钢复合风管', '㎡', '35', 'duct', 'composite'),
    (9, '橡塑包裹安装', '㎡', '17', 'wrap', ''),
    (10, '玻璃棉/柔性卷毯包裹安装', '㎡', '18', 'wrap', ''),
    (11, '防火板安装', '㎡', '38', 'wrap', ''),
    (12, '防火板+岩棉安装', '㎡', '40', 'wrap', ''),
    (13, '风机不区分大小', '台', '500', 'equipment', ''),
    (14, '壁式风机', '台', '300', 'equipment', ''),
    (15, '换气扇', '台', '100', 'equipment', ''),
    (16, '风阀', '个', '50', 'equipment', ''),
    (17, '远程控制机构', '个', '65', 'equipment', ''),
    (18, '单双层百叶风口', '个', '20', 'equipment', ''),
    (19, '自垂百叶/风管不燃', '个', '50', 'equipment', ''),
    (20, '正压送风口', '个', '65', 'equipment', ''),
    (21, '消声器/静压箱', '个', '300', 'equipment', ''),
    (22, '防火风口', '个', '60', 'equipment', ''),
    (23, '防雨百叶长+宽＜1.5m', '个', '50', 'equipment', ''),
    (24, '防雨百叶风口长+宽≥1.5m', '个', '100', 'equipment', ''),
    (25, '散流器', '个', '35', 'equipment', ''),
    (26, '消声/防雨弯头', '个', '65', 'equipment', ''),
]
SHAANXI_ROWS = [
    (3, '正压送风及地下室送风系统共板法兰风管', '㎡', '40', 'duct', 'common'),
    (4, '防排烟系统角铁法兰风管', '㎡', '45', 'duct', 'angle'),
    (5, '卫生间排风及空调系统共板法兰风管', '㎡', '50', 'duct', 'common'),
    (6, '不锈钢风管', '㎡', '110', 'duct', ''),
    (7, '螺旋/圆形风管', '㎡', '65', 'duct', ''),
    (8, '单/双面彩钢复合风管', '㎡', '60', 'duct', 'composite'),
    (9, '共板法兰安装辅材', '㎡', '10', 'auxiliary', 'common'),
    (10, '角铁法兰安装辅材', '㎡', '12', 'auxiliary', 'angle'),
    (11, '复合风管安装辅材', '㎡', '20', 'auxiliary', 'composite'),
    (12, '玻璃岩棉防火包裹', '㎡', '20', 'wrap', ''),
    (13, '硅酸钙板防火包裹', '㎡', '50', 'wrap', ''),
    (14, '散流器（空调系统）', '个', '35', 'equipment', ''),
    (15, '成品防火软连接/柔性软接头', '㎡', '45', 'equipment', ''),
    (16, '防虫/防鼠网', '个', '50', 'equipment', ''),
    (17, '消声/防雨弯头', '个', '65', 'equipment', ''),
    (18, '静压箱及消声器', '台', '280', 'equipment', ''),
    (19, '消防送/排风、排烟风机', '台', None, 'equipment', ''),
    (20, '油烟净化机、管道式排风机', '台', None, 'equipment', ''),
    (21, '换气扇（含排气扇、吸顶式换气扇）', '台', '100', 'equipment', ''),
    (22, '方形壁式换气扇', '个', '120', 'equipment', ''),
    (23, '防火阀/排烟阀/调节阀/对开多叶调节阀/止回阀', '套', '55', 'equipment', ''),
    (24, '远控常闭排烟阀/280℃排烟防火阀（连锁风机）', '个', '110', 'equipment', ''),
    (25, '单/双层百叶送/排风口（含可调单层百叶）', '个', '25', 'equipment', ''),
    (26, '防火风口', '个', '30', 'equipment', ''),
    (27, '自垂百叶风口', '个', '45', 'equipment', ''),
    (28, 'AH格栅风口/AH/D格栅风口（带阀）', '个', '45', 'equipment', ''),
    (29, '防火风口（消防常开弱电控制否 70℃熔断）', '个', '55', 'equipment', ''),
    (30, '可开链式百叶风口', '个', '60', 'equipment', ''),
    (31, '多叶送风口/正压送风口/电动防火风口', '个', '95', 'equipment', ''),
    (32, '电动多叶排烟风口（带手动远程控制机构）', '套', '95', 'equipment', ''),
    (33, '防雨百叶风口/板式排烟口/远程多叶送风口', '个', '110', 'equipment', ''),
]


def default_catalog():
    projects = []
    for prefix, rows, regions, sheet, col in [
        ('four', FOUR_ROWS, FOUR_REGIONS, SHEETS[0], 'D'),
        ('sx', SHAANXI_ROWS, ['陕西'], SHEETS[1], 'G'),
    ]:
        for row, name, unit, price, kind, auxiliary in rows:
            item = dict(id=f'{prefix}_{row}', name=name, unit=unit, price=price,
                        kind=kind, auxiliary_type=auxiliary, regions=list(regions),
                        source=f'{SOURCE_FILE} / {sheet}!{col}{row}', note='')
            if prefix == 'sx' and row in (19, 20):
                item['power_prices'] = ['300', '600', '800']
                item['source'] = f'{SOURCE_FILE} / {sheet}!G19:G20'
                item['note'] = '<1.5kW；1.5—17.5kW（含端点）；>17.5kW；双速档位须人工确认'
            if prefix == 'sx' and row in (14, 15, 16, 17, 22, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33):
                item['note'] += ' 已含原表所列小辅材；带阀、执行器或远控机构的项目不得重复收费'
            if prefix == 'four' and row in (7, 19):
                item['note'] = '原表存在名称或适用条件歧义，请报价员确认项目'
            if prefix == 'four' and row in (25, 26):
                item['source'] = '2026-09-28用户确认'
            if prefix == 'four' and row == 25:
                item['note'] = '带阀散流器按正常散流器安装计价，不叠加风阀安装费'
            projects.append(item)
    return dict(name='安装劳务销售价（20260928最新版）', company_id='guizhou',
                source=SOURCE_FILE, source_sha256=SOURCE_SHA256,
                effective_from=date.today().isoformat(), effective_to='',
                tax_factor='1.01', four_auxiliary_price='8', projects=projects)


def validate_catalog(body):
    if number(body.get('tax_factor')) != number('1.01'):
        raise RuleError('安装统一含税系数必须为1.01')
    if number(body.get('four_auxiliary_price')) < 0:
        raise RuleError('安装辅材单价不能为负')
    projects = body.get('projects')
    if not isinstance(projects, list) or not projects:
        raise RuleError('请配置安装项目')
    seen = set()
    for p in projects:
        if not isinstance(p, dict) or not p.get('id') or p['id'] in seen:
            raise RuleError('安装项目编号缺失或重复')
        seen.add(p['id'])
        if not p.get('name') or not p.get('source') or p.get('unit') not in {'㎡', '台', '个', '套'}:
            raise RuleError('安装项目名称、单位或依据不完整')
        if not isinstance(p.get('regions'), list) or not p['regions'] or any(not isinstance(r, str) or not r.strip() for r in p['regions']):
            raise RuleError('请配置安装项目适用地区')
        if p.get('kind') not in {'duct', 'wrap', 'equipment', 'auxiliary'}:
            raise RuleError('安装项目类型未识别')
        prices = p.get('power_prices')
        if prices is not None:
            if not isinstance(prices, list) or len(prices) != 3 or p.get('price') is not None:
                raise RuleError('风机安装功率档位必须为三档且不能同时配置固定价格')
        else:
            prices = [p.get('price')]
        if any(number(price) < 0 for price in prices):
            raise RuleError('安装销售单价不能为负')
    for kind in ('common', 'angle', 'composite'):
        if len([p for p in projects if p['kind'] == 'auxiliary' and '陕西' in p['regions'] and p.get('auxiliary_type') == kind]) != 1:
            raise RuleError('陕西共板、角铁、复合安装辅材必须各有一个明确项目')
