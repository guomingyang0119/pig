"""Read the supplied workbook; create a standalone, non-production rule guide."""
from pathlib import Path
from decimal import Decimal as D
from html import escape as e
import json
import openpyxl

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'outputs/2026-09-17报价整理/报价清单及计算规则_2026-09-17.xlsx'
OUT = ROOT / 'outputs/指标填写样例_20260917'
OUT.mkdir(exist_ok=True)
w = openpyxl.load_workbook(SOURCE, data_only=False)

def table(headers, rows):
    return '<div class="scroll"><table><thead><tr>'+''.join('<th>'+e(str(x))+'</th>' for x in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+e(str(x if x is not None else '待确认'))+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'

samples = [
 dict(name='单层百叶：先看懂这一条',source='算价示例!B10:K10；风口风阀!B6:I6',category='百叶风口',basis='计算成本后换算售价',units='个 → 个',match='product_type = 单层百叶（新增的明确产品类型字段；不要靠名称猜测）',inputs='width：宽，数字，mm，客户输入；height：高，数字，mm，客户输入',params='frame=60；factor=70；divisor=0.75；minimum=14',quantity='qty',cost='(width + frame) * (height + frame) * factor / 1000000',sale='max(cost / divisor, minimum)',plain='宽高各加60毫米，算面积并乘70得到成本；成本÷0.75，但销售单价不低于14元。',example='宽400、高400、数量1：成本14.812元；销售单价19.749333…元，展示约19.75元。宽100、高100时成本1.792元，销售单价执行最低价14元。',pending='材质适用范围、含税状态、是否含运费/安装、生效日期与正式精度待确认。'),
 dict(name='单层百叶带阀：一类配置一条规则',source='算价示例!B11:K11；风口风阀!第9行',category='百叶风口',basis='计算成本后换算售价',units='个 → 个',match='product_type = 单层百叶（带阀）',inputs='width：宽，数字，mm，客户输入；height：高，数字，mm，客户输入',params='frame=60；factor=140；divisor=0.75；minimum=24',quantity='qty',cost='(width + frame) * (height + frame) * factor / 1000000',sale='max(cost / divisor, minimum)',plain='带阀成本系数变为140，最低售价变为24元；不能和普通单层百叶同时匹配。',example='宽400、高400、数量1：成本29.624元；销售单价39.498666…元，展示约39.50元。',pending='材质、税费、安装/运费、生效日期待确认。'),
 dict(name='0.75mm镀锌共板直管：净面积结算',source='风管价目!第8、15、17、19行；算价示例!第6行有查列冲突',category='风管',basis='直接计算 / 引用正式销售价',units='节 → ㎡（仅本填写演示；客户若给㎡要用另一条规则）',match='material = 镀锌板；thickness = 0.75；flange = 共板；area_basis = 净面积；product_state = 直管非成品（示例标签，正式命名待确认）',inputs='width：宽，数字，mm；height：高，数字，mm；length：每节实际长度，数字，mm；均为客户输入',params='ton_price=4050；thick=0.75；density=7.85；processing=4；loss=1.17',quantity='(width + height) * 2 * length / 1000000 * qty',cost='留空：本例展示专表销售口径，不把它强行认作完整生产成本',sale='(ton_price * density * 0.001 * thick + processing) * loss',plain='先算净面积，再按专表单价计价。材料费23.844375，加工费4，乘净面积共板系数1.17；本例不再÷0.75，也不包含成品合口6与辅材2。',example='宽800、高400、每节长2000、1节：面积4.8㎡；单价32.57791875元/㎡；未舍入行金额156.37401元。成品共板另加6+2后单价40.57791875元/㎡。',pending='客户数量单位、生效日期、整行税口径、运费/安装、是否成品及精度待确认；4050是文件历史值，不代表今日钢价。'),
 dict(name='HTFC-20：只演示确定型号后的价格',source='风机价目!B9:L9（实际数据至K列）及第3行',category='风机',basis='计算成本后换算售价',units='台 → 台',match='model = HTFC-20；usage = 普通（不将排烟等同排油烟）',inputs='本算价样例不设客户尺寸变量；正式规则还须关联已确认的风量、风压、功率配置',params='housing=532；impeller=447；drive=400；auxiliary=70；motor=700；divisor=0.75',quantity='qty',cost='housing + impeller + drive + auxiliary + motor',sale='cost / divisor',plain='机壳、叶轮、传动、辅材、电机成本合计后÷0.75；只用于说明怎么填，不表示机号匹配即可完成选型。',example='成本532+447+400+70+700=2149元；单价2865.333333…元。HTFC-12的822元缺电机成本，1096元不能作为完整报价。',pending='电机对应功率及有效价格、风量风压配对参数、选型条件、税费和生效日期未齐，禁止直接发布本样例。'),
 dict(name='圆软接：公式直接得到含税售价',source='算价示例!第28行；防火包裹!第21行',category='其他材料（当前平台若无此品类，先补品类，不硬归为风管）',basis='直接计算 / 引用正式销售价',units='个 → 个',match='product_type = 圆软接',inputs='diameter：直径，数字，m，客户输入（注意不是mm）',params='pi_value=3.14；rate=260；fixed_length=0.2',quantity='qty',cost='留空：文件没有独立成本',sale='diameter * pi_value * rate * fixed_length',plain='直径按米输入；乘3.14、260和固定长度0.2得到含税销售价，不再÷0.75。',example='直径0.5m、数量1：0.5×3.14×260×0.2=81.64元。',pending='材料/连接方式范围、固定0.2m是否始终适用、运费安装、生效日期待确认；不能再次重复加已含税额。'),
]

intro='''<section class="hero"><small>贵州报价 · 指标填写样例 · 2026-09-17</small><h1>把“指标”理解为一张算价配方</h1><p>选什么产品 → 客户提供什么 → 领导维护什么 → 按什么公式算 → 拿样例核对。</p><p class="warning">这是依据指定Excel整理的独立教学样例，未写入正式数据库、未发布规则。源文件中的冲突保留待确认。所有近似金额仅便于阅读，不代表正式舍入设置。</p></section>
<section><h2>先分清平台里的三类内容</h2>'''+table(['平台名称','通俗理解','例如','以后谁修改'],[
('价格','会变动的单价','镀锌板吨价、电机成本','领导维护新版本'),('计算规则','怎样把条件算成价格','百叶成本÷0.75并设最低价','领导维护并核对样例'),('产品配置 / 候选','可以选择的完整产品参数组合','某风机型号+功率+同一工况风量/风压','领导维护，报价员确认候选')])+'''<p>“固定参数”是这条算法里的系数，如60、70、0.75；“必填变量”是每次报价会变化的尺寸。qty由清单数量提供，cost是公式计算出的单位成本，都不用再建同名变量。</p><p>吨价等易变价格，正式使用时应放在“价格”里，再在规则的“引用价格库”中绑定。下面为方便看懂先在样例中展开数字，不将历史价格自动设为现价。</p></section>'''

cards=[]
for i,s in enumerate(samples,1):
    fields=[('名称 *','【填写样例/未发布】'+s['name']),('产品类别 *',s['category']),('销售方式 *',s['basis']),('客户清单单位 → 销售计价单位',s['units']),('适用条件 · 精确匹配',s['match']),('必填变量',s['inputs']),('固定参数',s['params']),('计费数量公式 *',s['quantity']),('单位成本公式',s['cost']),('销售单价公式 *',s['sale']),('正式依据 / 文件位置 / 确认说明 *',SOURCE.name+'；'+s['source']+'；教学样例，尚未批准用于成交'),('生效日期 / 税费与包含项',s['pending'])]
    cards.append('<section id="sample'+str(i)+'"><small>样例 '+str(i)+'</small><h2>'+e(s['name'])+'</h2><p>'+e(s['plain'])+'</p><div class="result">'+e(s['example'])+'</div><details><summary>展开：在现有指标管理页面逐项怎么填</summary>'+table(['平台字段','填写内容'],fields)+'</details><p class="warning">待确认：'+e(s['pending'])+'</p><p class="source">来源：'+e(s['source'])+'</p></section>')

catalog=[]
for row in list(w['报价清单'].values)[5:]:
    catalog.append([row[1],row[2],row[6],row[9]])
louvres=[]
for n in range(6,30):
    r=[w['风口风阀'].cell(n,c).value for c in range(1,12)]
    louvres.append([n,r[1],r[4],r[8]])
tail='''<section><h2>完整范围：表里其他规则怎么处理</h2><p>下表保留文件原有分类和算法。标为“可自动报价”是源表描述，不代表本系统已经发布；全部仍需核对适用条件和费用口径。</p>'''+table(['类别','产品','文件销售算法','源表状态'],catalog)+'''</section><section><h2>百叶风口扩展清单</h2><p>每种配置应分开维护。下列是文件中的“原表公式文字”，其中B3、G3等指向原始表格，不可直接复制到系统公式框；系统应改用本行width、height等变量。ABS引用错行问题尚需核对，不能照抄已有示例结果。</p>'''+table(['源表行','产品','原表公式（只作追溯）','最低销售价说明'],louvres)+'''</section>
<section><h2>发现的冲突与不能直接套用的地方</h2><ul>
<li><b>风管查列错误：</b>算价示例!I6、风管价目!I25的VLOOKUP从C:P取第13列，实际是O列“净面积共板”，不是P列；正确返回32.57791875。应按实际公式核验，不能凭视觉位置猜列。</li>
<li><b>HTFC-12缺电机：</b>算价示例给822与1096，但风机价目明确电机为空；演示改用HTFC-20说明价格构成，仍不替代选型。</li>
<li><b>不同商品不是统一÷0.75：</b>百叶/自产风机是÷0.75；消声设备源表是×1.25；防火包裹材料子表是÷0.85；风管按专表；不能把不同口径合并。</li>
<li><b>风阀只有部件概述和示例合计：</b>本工作簿不足以重建任意尺寸的全部部件公式；不能把630×200的66.489482466元当作所有风阀成本。</li>
<li><b>风机选型未补齐：</b>本文件不足以确定功率、风压、风量的配对曲线和容差；不根据“越大越小”关系推算不存在的参数。</li>
<li><b>外购与防爆：</b>不含税采购价不是最终销售价；异常参数、档位边界及重叠需确认，不自动更正。</li>
<li><b>不锈钢：</b>201/304当期吨价缺失；保留材料费+15的算法待确认，不填0。</li>
<li><b>生效日期：</b>文件整理日期不等于规则正式生效日期。安装、运费和税口径未明确时留空，不为了通过发布随便选“不含”。</li>
</ul></section>
<section><h2>以后实际填写：按这六步</h2><ol><li>先写中文：这是哪个产品，适用于哪些材质、工艺、尺寸单位？</li><li>列客户输入：宽、高、长、数量；列领导维护项：价格、系数、最低价。</li><li>进入“指标管理 → 计算规则 → 新增计算规则”，对照上面的展开表填写，先保存草稿。</li><li>易变价格先建价格记录，核对并发布后在规则里引用；不要同时在固定参数和价格引用里建同名变量。</li><li>补齐生效日期、税口径、是否含安装/运费；拿已确认业务样例独立算一个预期金额，再试算比较。循环小数的正式预期额须按已确认精度填写，不用本页约数直接发布。</li><li>核对无误后由领导发布；报价员只选条件、确认候选。变更时创建新版本，不改历史正式报价。</li></ol>
<h3>平台为什么难懂，以及建议怎么改</h3><p>现在页面把英文变量、公式、价格引用同时展示，更像技术配置工具。建议改为“产品类型 → 适用条件 → 输入与价格 → 中文算法 → 样例核对”的向导，英文公式折叠为高级设置；先提供百叶、风管、按型号风机三种模板。此处只是建议，本次没有重构平台，也没有更改权限或正式数据。</p></section>'''
# Corrected after verifying Excel's 1-based column index: C + 12 = O.
tail=tail.replace('<b>风管查列错误：</b>', '<b>风管查列已核验：</b>')
html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>指标填写样例与说明</title><style>body{margin:0;background:#f5f5f0;color:#233c36;font:16px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1100px;margin:auto;padding:35px 24px}section{background:white;padding:28px 32px;margin:22px 0;border:1px solid #dde5df;border-radius:16px}.hero{background:#153f35;color:white}h1{font-size:34px;line-height:1.4}h2{font-size:23px}small,.source{color:#70877e}.hero small{color:#b7d5c5}.warning{background:#fff2dd;color:#795011;padding:14px;border-radius:8px}.result{background:#e5f2eb;padding:18px;border-left:4px solid #268363;margin:16px 0}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;vertical-align:top;padding:12px;border-bottom:1px solid #dfe8e1;overflow-wrap:anywhere}th{background:#eef4ef}td:first-child{min-width:120px}details{margin:20px 0}summary{cursor:pointer;font-weight:bold;color:#176c50;padding:12px;background:#f0f6f2}.scroll{overflow:auto}li{margin:10px 0}@media print{section{break-inside:avoid}details{display:block}}</style><main>'''+intro+''.join(cards)+tail+'</main></html>'
(OUT/'指标填写样例与说明.html').write_text(html,encoding='utf-8')
(OUT/'指标样例资料.json').write_text(json.dumps({'status':'教学资料，未发布，不是系统导入文件','source':str(SOURCE),'samples':samples},ensure_ascii=False,indent=2),encoding='utf-8')
assert (D('4050')*D('7.85')*D('.001')*D('.75')+4)*D('1.17')*D('4.8') == D('156.374010000')
assert (D(400)+60)*(D(400)+60)*70/D(1000000)==D('14.812')
assert sum(map(D,['532','447','400','70','700']))==D(2149)
assert D('.5')*D('3.14')*260*D('.2')==D('81.6400')
print(OUT/'指标填写样例与说明.html')
