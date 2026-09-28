import fs from "node:fs/promises";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputDir = "D:/pig/outputs/internal-quote-template-20260926";
const outputPath = `${outputDir}/内部工程清单统一模板.xlsx`;
const previewPath = `${outputDir}/内部工程清单统一模板-预览.png`;
const font = "Microsoft YaHei";
const dataStart = 10;
const dataEnd = 309;
const workbook = Workbook.create();
const sheet = workbook.worksheets.add("工程清单");

sheet.showGridLines = false;
sheet.tabColor = "#5B6573";
sheet.getRange(`A1:I${dataEnd}`).format.font = { name: font, size: 10, color: "#2F343B" };

// 标题区沿用系统正式报价单的排版层级。
sheet.mergeCells("A1:G2");
sheet.getRange("A1").values = [["内 部 综 合 工 程 清 单"]];
sheet.getRange("A1:G2").format.font = { name: font, size: 20, bold: true, color: "#2F343B" };
sheet.getRange("A1:G2").format.horizontalAlignment = "center";
sheet.getRange("A1:G2").format.verticalAlignment = "center";
sheet.mergeCells("A3:G3");
sheet.getRange("A3").values = [["贵州科庆通风设备有限公司"]];
sheet.getRange("A3:G3").format.font = { name: font, size: 11, bold: true, color: "#2F343B" };
sheet.getRange("A3:G3").format.horizontalAlignment = "center";

sheet.getRange("H1:I3").values = [
  ["模板用途", "报价系统导入"],
  ["填表日期", ""],
  ["整单选项", "导入后在系统确认"],
];
sheet.getRange("H1:H3").format.fill = "#F2F3F5";
sheet.getRange("H1:H3").format.font = { name: font, size: 9, bold: true, color: "#2F343B" };
sheet.getRange("H1:I3").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
sheet.getRange("H1:I3").format.verticalAlignment = "center";
sheet.getRange("H1:I3").format.horizontalAlignment = "center";
sheet.getRange("I2").format.fill = "#FFF7DC";
sheet.getRange("I2").format.numberFormat = "yyyy-mm-dd";

sheet.getRange("A4:I4").format.rowHeight = 8;
sheet.getRange("A5:I6").values = [
  ["客户名称", "", "", "", "工程名称", "", "", "", ""],
  ["联系人", "", "", "", "交付地点", "", "", "", ""],
];
sheet.mergeCells("B5:D5");
sheet.mergeCells("F5:I5");
sheet.mergeCells("B6:D6");
sheet.mergeCells("F6:I6");
for (const address of ["A5", "E5", "A6", "E6"]) {
  sheet.getRange(address).format.fill = "#F2F3F5";
  sheet.getRange(address).format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
  sheet.getRange(address).format.horizontalAlignment = "center";
}
sheet.getRange("A5:I6").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
sheet.getRange("B5:D6").format.fill = "#FFF7DC";
sheet.getRange("F5:I6").format.fill = "#FFF7DC";
sheet.getRange("A5:I6").format.verticalAlignment = "center";

sheet.getRange("A7").values = [["填写说明"]];
sheet.mergeCells("B7:I7");
sheet.getRange("B7").values = [["风管、风机、风阀、风口及其他设备可混填在同一张表。产品类型通常留空，由系统自动识别；名称含义不明确时再选择。风机把型号、风量、风压、功率、电压写在“规格型号”；风管名称写明材质，并填写厚度和法兰方式。"]];
sheet.getRange("A7").format.fill = "#F2F3F5";
sheet.getRange("A7").format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
sheet.getRange("A7").format.horizontalAlignment = "center";
sheet.getRange("B7:I7").format.fill = "#FFF7DC";
sheet.getRange("B7:I7").format.font = { name: font, size: 9, color: "#5D636B" };
sheet.getRange("A7:I7").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
sheet.getRange("A7:I7").format.verticalAlignment = "center";
sheet.getRange("B7:I7").format.wrapText = true;

sheet.mergeCells("A8:I8");
sheet.getRange("A8").values = [["清单明细"]];
sheet.getRange("A8:I8").format.font = { name: font, size: 11, bold: true, color: "#2F343B" };
sheet.getRange("A8:I8").format.verticalAlignment = "center";
sheet.getRange("A8:I8").format.borders = { bottom: { style: "medium", color: "#707780" } };

const headers = ["序号", "产品名称", "产品类型", "规格型号", "厚度(mm)", "法兰方式", "单位", "数量", "备注"];
sheet.getRange("A9:I9").values = [headers];
sheet.getRange(`A${dataStart}:I${dataEnd}`).values = Array.from({ length: dataEnd - dataStart + 1 }, () => Array(9).fill(""));
const table = sheet.tables.add(`A9:I${dataEnd}`, true, "ProjectItems");
table.style = "TableStyleLight9";
table.showFilterButton = true;
table.showBandedRows = true;
sheet.getRange("A9:I9").format.fill = "#EFF1F3";
sheet.getRange("A9:I9").format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
sheet.getRange("A9:I9").format.horizontalAlignment = "center";
sheet.getRange("A9:I9").format.verticalAlignment = "center";
sheet.getRange("A9:I9").format.borders = { preset: "all", style: "thin", color: "#CDD2D8" };
sheet.getRange(`A${dataStart}:I${dataEnd}`).format.borders = { preset: "all", style: "thin", color: "#E0E3E7" };
sheet.getRange(`A${dataStart}:I${dataEnd}`).format.verticalAlignment = "center";
sheet.getRange(`A${dataStart}:A${dataEnd}`).format.horizontalAlignment = "center";
sheet.getRange(`C${dataStart}:H${dataEnd}`).format.horizontalAlignment = "center";
sheet.getRange(`B${dataStart}:F${dataEnd}`).format.wrapText = true;
sheet.getRange(`I${dataStart}:I${dataEnd}`).format.wrapText = true;
sheet.getRange(`E${dataStart}:E${dataEnd}`).format.numberFormat = "0.###";
sheet.getRange(`H${dataStart}:H${dataEnd}`).format.numberFormat = "0.###";

sheet.getRange(`C${dataStart}:C${dataEnd}`).dataValidation = {
  rule: { type: "list", values: ["风管", "风机", "风阀", "百叶风口", "消声设备", "外购设备", "其他材料"] },
};
sheet.getRange(`F${dataStart}:F${dataEnd}`).dataValidation = {
  rule: { type: "list", values: ["共板法兰", "角钢法兰", "圆法兰"] },
};
sheet.getRange(`G${dataStart}:G${dataEnd}`).dataValidation = {
  rule: { type: "list", values: ["㎡", "台", "个", "只", "套", "件", "组", "樘", "米", "m", "m³", "项"] },
};

const missing = { fill: "#FCE8E6", font: { bold: true, color: "#B42318" } };
sheet
  .getRange(`B${dataStart}:B${dataEnd}`)
  .conditionalFormats.addCustom(`=AND($B${dataStart}="",OR($C${dataStart}<>"",$D${dataStart}<>"",$E${dataStart}<>"",$F${dataStart}<>"",$G${dataStart}<>"",$H${dataStart}<>"",$I${dataStart}<>""))`, missing);
sheet.getRange(`G${dataStart}:G${dataEnd}`).conditionalFormats.addCustom(`=AND($B${dataStart}<>"",$G${dataStart}="")`, missing);
sheet.getRange(`H${dataStart}:H${dataEnd}`).conditionalFormats.addCustom(`=AND($B${dataStart}<>"",$H${dataStart}="")`, missing);

const widths = { A: 7, B: 22, C: 13, D: 32, E: 12, F: 14, G: 10, H: 12, I: 26 };
for (const [column, width] of Object.entries(widths)) sheet.getRange(`${column}:${column}`).format.columnWidth = width;
sheet.getRange("A1:I1").format.rowHeight = 26;
sheet.getRange("A2:I2").format.rowHeight = 26;
sheet.getRange("A3:I3").format.rowHeight = 22;
sheet.getRange("A5:I6").format.rowHeight = 23;
sheet.getRange("A7:I7").format.rowHeight = 44;
sheet.getRange("A8:I8").format.rowHeight = 24;
sheet.getRange("A9:I9").format.rowHeight = 30;
sheet.getRange(`A${dataStart}:I${dataEnd}`).format.rowHeight = 22;
sheet.freezePanes.freezeRows(9);
sheet.freezePanes.freezeColumns(2);

workbook.recalculate();
await fs.mkdir(outputDir, { recursive: true });
const inspection = await workbook.inspect({
  kind: "table",
  range: "工程清单!A1:I14",
  include: "values,formulas",
  tableMaxRows: 14,
  tableMaxCols: 9,
  maxChars: 8000,
});
console.log(inspection.ndjson);
const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 100 },
  summary: "final formula error scan",
});
console.log(errors.ndjson);
const preview = await workbook.render({ sheetName: "工程清单", range: "A1:I18", scale: 1, format: "png" });
await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
console.log(JSON.stringify({ outputPath, previewPath }));
