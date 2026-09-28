"""Generate import-only reference cases; never changes the Python source or price rules."""
import argparse
import copy
import hashlib
import inspect
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.source))
from quotation.excel_io import auto_classify_row, suggest_auto_mapping

rows = []
for name in ['镀锌风管', '风管防火阀', '防火阀', '电动防火阀', '电动调节阀', '手动调节阀', '止回阀', '余压阀',
             '多叶排烟口', '板式排烟口', '百叶风口', '消声器', '静压箱', '消声弯头', '壁式轴流风机', '换气扇', '未知材料']:
    for category in ['', '风管', '外购设备']:
        for unit in ['㎡', '个']:
            row = dict(name=name, category=category, unit=unit, engineering_quantity='2.5', quantity='',
                       attributes={}, row_id='source-row', source_row=3, installation={'unit_price': '7'})
            rows.append({'input': row, 'expected': auto_classify_row(copy.deepcopy(row))})
for unit in ['㎡', 'm2', 'm²', '平方米', '台', '个', '只', '套', '件', '组', '樘', '吨', '']:
    row = dict(name='镀锌风管', category='', unit=unit, engineering_quantity='', quantity='12', attributes={})
    rows.append({'input': row, 'expected': auto_classify_row(copy.deepcopy(row))})
sheets = []
for header in [['产品名称', '规格型号', '工程量', '单位'], ['材料名称', '规格', '数量', '计量单位'],
               ['产品名称', '规格描述', '规格型号', '工程量', '数量', '单位'],
               ['设备名称', '规格型号', '数量', '功率(kW)', '单位'],
               ['产品名称', '规格', '规格描述', '单位'], ['产品名称', '产品名称', '工程量'],
               ['项目名称', '工程量（㎡）', '圆法兰数量', '备注'], ['无可识别表头']]:
    sheet = {'name': '清单', 'rows': [{'number': 1, 'cells': header, 'issues': []}]}
    sheets.append({'input': sheet, 'expected': suggest_auto_mapping(sheet)})
out = Path(__file__).parent
source = args.source / 'quotation' / 'excel_io.py'
(out / 'auto-import-reference.py.txt').write_text(inspect.getsource(suggest_auto_mapping) + '\n' + inspect.getsource(auto_classify_row), encoding='utf-8')
(out / 'auto-import-golden.json').write_text(json.dumps({
    'reference_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'rows': rows, 'sheets': sheets,
}, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Generated {len(rows)} classification and {len(sheets)} mapping reference cases.')
