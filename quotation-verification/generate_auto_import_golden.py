# 仅生成导入识别对照，不执行价格计算或修改冻结 Python 规则。
# 执行会更新本目录的识别 golden JSON 和识别函数文本快照，生产导入使用 Java 实现。
"""仅生成导入识别对照用例，不修改 Python 参考源码或价格规则。"""
import argparse
import copy
import hashlib
import inspect
import json
from pathlib import Path
import sys

# --source 明确指向要核对的 Python 原项目目录，避免自动选择其他参考版本。
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.source))
from quotation.excel_io import auto_classify_row, suggest_auto_mapping

# 产品名称、显式类别与计量单位组合验证逐行类别识别及工程量/数量分派。
rows = []
for name in ['镀锌风管', '风管防火阀', '防火阀', '电动防火阀', '电动调节阀', '手动调节阀', '止回阀', '余压阀',
             '多叶排烟口', '板式排烟口', '百叶风口', '消声器', '静压箱', '消声弯头', '壁式轴流风机', '换气扇', '未知材料']:
    for category in ['', '风管', '外购设备']:
        for unit in ['㎡', '个']:
            row = dict(name=name, category=category, unit=unit, engineering_quantity='2.5', quantity='',
                       attributes={}, row_id='source-row', source_row=3, installation={'unit_price': '7'})
            rows.append({'input': row, 'expected': auto_classify_row(copy.deepcopy(row))})
# 进一步覆盖面积、台/个等计数、吨和空单位，确认未知单位不被强行转换。
for unit in ['㎡', 'm2', 'm²', '平方米', '台', '个', '只', '套', '件', '组', '樘', '吨', '']:
    row = dict(name='镀锌风管', category='', unit=unit, engineering_quantity='', quantity='12', attributes={})
    rows.append({'input': row, 'expected': auto_classify_row(copy.deepcopy(row))})
# 独立生成表头建议用例，包含名称/规格/数量歧义和完全不能识别的表头。
sheets = []
for header in [['产品名称', '规格型号', '工程量', '单位'], ['材料名称', '规格', '数量', '计量单位'],
               ['产品名称', '规格描述', '规格型号', '工程量', '数量', '单位'],
               ['设备名称', '规格型号', '数量', '功率(kW)', '单位'],
               ['产品名称', '规格', '规格描述', '单位'], ['产品名称', '产品名称', '工程量'],
               ['项目名称', '工程量（㎡）', '圆法兰数量', '备注'], ['无可识别表头']]:
    sheet = {'name': '清单', 'rows': [{'number': 1, 'cells': header, 'issues': []}]}
    sheets.append({'input': sheet, 'expected': suggest_auto_mapping(sheet)})
# 输出位置固定到脚本目录，来源文件仅被读取并记录 SHA-256。
out = Path(__file__).parent
source = args.source / 'quotation' / 'excel_io.py'
# 保存当次识别函数源码文本用于追溯；行输入和字段建议结果同时存入 JSON。
(out / 'auto-import-reference.py.txt').write_text(inspect.getsource(suggest_auto_mapping) + '\n' + inspect.getsource(auto_classify_row), encoding='utf-8')
(out / 'auto-import-golden.json').write_text(json.dumps({
    'reference_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'rows': rows, 'sheets': sheets,
}, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Generated {len(rows)} classification and {len(sheets)} mapping reference cases.')
