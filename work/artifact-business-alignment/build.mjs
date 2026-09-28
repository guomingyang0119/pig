import fs from "node:fs/promises";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputDir = "D:/pig/outputs/01a0c9a8-4d36-7ae1-9fd4-e2e81344a1f0";
const outputPath = `${outputDir}/报价系统业务问题对齐清单.xlsx`;
const previewPath = "D:/pig/work/artifact-business-alignment/preview.png";

const workbook = Workbook.create();
const sheet = workbook.worksheets.add("业务问题对齐");
sheet.showGridLines = false;

sheet.getRange("A1:I18").format.fill = "#FFFFFF";
sheet.getRange("A2").values = [["报价系统业务问题对齐清单"]];
sheet.getRange("A3").values = [["适用测试单：马板变电站通风报价单.xlsx"]];
sheet.getRange("A4").values = [["填写说明：请在“最终确认口径”和“确认状态”列填写。确认完成后再将规则固化到系统。"]];

const headers = [["编号", "业务主题", "当前情况", "必须确认的问题", "参考选项或示例", "最终确认口径", "负责人", "确认状态", "备注"]];
sheet.getRange("A6:I6").values = headers;

const rows = [
  [1, "“防雨小于1.5m”的定义", "安装指标同时存在“单双层百叶风口；防雨小于1.5m”25元/个和“防雨百叶风口”50元/个。", "1.5m具体指最长边、宽度、高度、周长还是面积？等于1.5m时归入哪一档？", "最长边＜1.5m；或面积＜1.5㎡。需要明确唯一口径。", "", "待确认", ""],
  [2, "防雨百叶风口自动选价", "测试单包含1250×800、200×200、2200×1200三种防雨百叶风口，目前全部要求人工选择。", "尺寸满足“小于1.5m”时是否自动取25元，其余自动取50元？产品名称完全等于“防雨百叶风口”时是否优先取50元？", "按第1项确认的尺寸规则自动分档；无法解析尺寸时保留待确认。", "", "待确认", ""],
  [3, "电动防火风口的安装组成", "指标中有“防火风口”60元/个和“远程控制机构”65元/个，但一行产品只能选择一个安装项目。", "电动防火风口安装费是60元、65元，还是60+65=125元？控制机构是否已包含在风口安装中？", "A：仅防火风口；B：仅控制机构；C：两个项目叠加。", "", "待确认", ""],
  [4, "电动多叶调节阀的安装组成", "指标中有“风阀”40元/个和“远程控制机构”65元/个，当前无法判断是否叠加。", "电动对开多叶调节阀安装费是40元、65元，还是40+65=105元？", "明确电动执行机构是否另计安装费。", "", "待确认", ""],
  [5, "风机附带百叶的安装组成", "轴流风机、边墙轴流风机名称中带自垂百叶或防雨百叶，当前因组合描述无法自动匹配。", "风机安装费与百叶安装费是否叠加？百叶作为风机附件时是否只计风机安装？", "A：只计风机；B：风机+百叶；C：按整套另定价格。", "", "待确认", ""],
  [6, "280℃全自动排烟阀归类", "程序没有把“排烟阀”自动归入现有“风阀”安装项目。", "280℃全自动排烟阀是否统一按风阀40元/个安装？是否存在温度、执行机构或尺寸加价？", "若无额外差异，可统一映射为“风阀”。", "", "待确认", ""],
  [7, "280℃板式排烟风口归类", "安装指标中没有同名项目，现有接近项目为“防火风口”60元/个。", "板式排烟风口是否按防火风口60元/个？常闭、电动机构是否需要另加费用？", "明确本体项目及附加机构是否叠加。", "", "待确认", ""],
  [8, "排气扇材料与安装口径", "管道式排气扇、窗式排气扇的安装可按换气扇100元/台，但材料供货分类目前无法识别。", "排气扇是否统一归为外购设备？材料价格来自固定目录、采购价还是报价员手填？安装是否统一按换气扇100元/台？", "分别确认材料定价来源和安装项目。", "", "待确认", ""],
  [9, "自垂百叶风口材料成本", "安装指标有自垂百叶项目，但当前材料指标没有可靠成本依据。", "自垂百叶风口的材料成本按什么公式或价表计算？是否按普通百叶面积价再加机构费用？", "提供成本公式、固定单价或可维护的价格表。", "", "待确认", ""],
  [10, "消声弯头材料及安装口径", "材料报价需要每个弯头的人工计算面积/方量；安装指标只有“消声器/静压箱”300元/个。", "1000×500等截面尺寸如何换算每个消声弯头面积？安装是否按300元/个，还是建立独立的消声弯头安装价？", "明确面积展开公式、长度/倍数和安装项目。", "", "待确认", ""],
  [11, "楼层高度与高空附加费", "原清单含0、3.5、8.5、14、19、24、30米层；系统当前只保存一个整单施工高度。", "高空附加费按整单一次填写，还是按楼层/产品分别计算？楼层标题是否应自动关联其后的产品行？", "A：整单一次；B：逐楼层；C：逐产品。需要明确阈值及金额算法。", "", "待确认", ""],
  [12, "相同产品确认结果复用", "同名同规格产品在多个楼层重复出现，目前需要逐行确认安装项目。", "同一订单内名称、规格、单位及地区相同的产品，确认一次后是否可自动应用到全部相同行？能否跨订单复用？", "建议先确认“同订单内批量应用”，跨订单复用需纳入正式映射规则。", "", "待确认", ""]
].map((row) => [...row.slice(0, 6), "", ...row.slice(6)]);
sheet.getRange("A7:I18").values = rows;

const all = sheet.getRange("A2:I18");
all.format.font = { name: "Arial", size: 10, color: "#222222" };
all.format.verticalAlignment = "top";

sheet.getRange("A2").format.font = { name: "Arial", size: 15, bold: true, color: "#111111" };
sheet.getRange("A3:A4").format.font = { name: "Arial", size: 10, color: "#555555", italic: true };

const header = sheet.getRange("A6:I6");
header.format.font = { name: "Arial", size: 10, bold: true, color: "#111111" };
header.format.horizontalAlignment = "center";
header.format.verticalAlignment = "center";
header.format.borders = {
  top: { style: "medium", color: "#777777" },
  bottom: { style: "medium", color: "#777777" },
  insideVertical: { style: "thin", color: "#D9D9D9" }
};

const body = sheet.getRange("A7:I18");
body.format.wrapText = true;
body.format.borders = {
  insideHorizontal: { style: "thin", color: "#D9D9D9" },
  insideVertical: { style: "thin", color: "#E5E5E5" },
  bottom: { style: "thin", color: "#BFBFBF" }
};
sheet.getRange("A7:A18").format.horizontalAlignment = "center";
sheet.getRange("G7:H18").format.horizontalAlignment = "center";
sheet.getRange("F7:H18").format.font = { name: "Arial", size: 10, color: "#111111" };

sheet.getRange("H7:H18").dataValidation = {
  rule: { type: "list", values: ["待确认", "已确认", "不适用"] }
};

sheet.getRange("A:A").format.columnWidth = 7;
sheet.getRange("B:B").format.columnWidth = 22;
sheet.getRange("C:C").format.columnWidth = 39;
sheet.getRange("D:D").format.columnWidth = 43;
sheet.getRange("E:E").format.columnWidth = 37;
sheet.getRange("F:F").format.columnWidth = 32;
sheet.getRange("G:G").format.columnWidth = 12;
sheet.getRange("H:H").format.columnWidth = 12;
sheet.getRange("I:I").format.columnWidth = 24;

sheet.getRange("2:2").format.rowHeight = 24;
sheet.getRange("3:4").format.rowHeight = 18;
sheet.getRange("6:6").format.rowHeight = 24;
sheet.getRange("7:18").format.rowHeight = 64;
sheet.freezePanes.freezeRows(6);

workbook.recalculate();

const inspect = await workbook.inspect({
  kind: "table",
  range: "业务问题对齐!A2:I18",
  include: "values,formulas",
  tableMaxRows: 20,
  tableMaxCols: 9,
  maxChars: 12000
});
console.log(inspect.ndjson);

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 100 },
  summary: "final formula error scan"
});
console.log(errors.ndjson);

const preview = await workbook.render({ sheetName: "业务问题对齐", range: "A1:I18", scale: 1 });
await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));

await fs.mkdir(outputDir, { recursive: true });
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
console.log(JSON.stringify({ outputPath, previewPath }));
