import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const sourcePath = "D:/pig/pig-upms/pig-upms-biz/src/main/resources/quotation/quotation-template.xlsx";
const outputDir = "D:/pig/outputs/01a0dc7a-9e62-7611-bc3c-ecd84bb3b955";
const outputPath = `${outputDir}/统一报价输入模板-20260926.xlsx`;
const previewDir = "D:/pig/work/verify_internal_template/unified-previews";

const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(sourcePath));

const colors = {
  input: "#FFF2CC",
  system: "#E7EBEF",
  note: "#EAF2F8",
  text: "#2D3338",
  muted: "#697077",
  border: "#AEB7BF",
};

function setFill(sheet, address, color) {
  sheet.getRange(address).format.fill = color;
}

function setText(sheet, address, value) {
  sheet.getRange(address).values = [[value]];
}

function prepareDataRows(sheet, lastCol) {
  const footerRange = sheet.getRange(`A16:${lastCol}30`);
  footerRange.unmerge();
  footerRange.clear({ applyTo: "contents" });

  for (let row = 10; row <= 29; row += 1) {
    sheet.getRange(`A${row}:${lastCol}${row}`).copyFrom(sheet.getRange(`A10:${lastCol}10`), "all");
  }
  sheet.getRange(`A10:${lastCol}29`).clear({ applyTo: "contents" });
  sheet.getRange(`A10:${lastCol}29`).format.rowHeight = 24;
}

function styleCommon(sheet, lastCol, priceRange, remarkRange) {
  setText(sheet, "B5", "请填写客户名称");
  setText(sheet, "B6", "请填写联系人及电话");
  setText(sheet, "B7", "导入后在系统确认");

  setText(sheet, "A8", "报价明细（黄色区域由员工填写；灰色区域由系统生成）");
  setFill(sheet, `A8:${lastCol}8`, colors.note);

  setText(sheet, "D9", "规格型号");
  setText(sheet, "E9", "法兰方式");

  setFill(sheet, "A10:A29", colors.system);
  setFill(sheet, "B10:G29", colors.input);
  setFill(sheet, priceRange, colors.system);
  setFill(sheet, remarkRange, colors.input);

  sheet.getRange(`A10:${lastCol}29`).format.verticalAlignment = "center";
  sheet.getRange(`A10:${lastCol}29`).format.borders = {
    preset: "all",
    style: "thin",
    color: "#D8DDE1",
  };
  sheet.getRange("B10:B29").format.horizontalAlignment = "left";
  sheet.getRange("D10:E29").format.horizontalAlignment = "left";
  sheet.getRange(remarkRange).format.horizontalAlignment = "left";
  sheet.getRange("F10:G29").format.horizontalAlignment = "center";

  sheet.dataValidations.add({
    range: "F10:F29",
    rule: { type: "list", values: ["㎡", "m²", "个", "台", "套", "米", "m", "kg"] },
  });

  sheet.mergeCells(`A30:${lastCol}30`);
  setText(
    sheet,
    "A30",
    "报价说明：两个工作表任选其一填写；产品名称、规格型号、法兰方式、单位和数量请尽量填写完整。价格、金额、合计、报价编号及商务条款由系统生成。",
  );
  const note = sheet.getRange(`A30:${lastCol}30`);
  note.format.fill = colors.note;
  note.format.font = { name: "Microsoft YaHei", size: 9, color: colors.text };
  note.format.wrapText = true;
  note.format.verticalAlignment = "center";
  note.format.horizontalAlignment = "left";
  note.format.rowHeight = 38;
  note.format.borders = { preset: "outside", style: "thin", color: colors.border };

  sheet.freezePanes.freezeRows(9);
}

function prepareSingleMaterial() {
  const sheet = workbook.worksheets.getItem("单层材料");
  prepareDataRows(sheet, "J");
  styleCommon(sheet, "J", "H10:I29", "J10:J29");
  setText(sheet, "C1", "单 层 材 料 报 价 输 入 单");
  setText(sheet, "H1", "系统生成");
  setText(sheet, "H2", "系统生成");
  setText(sheet, "H3", "系统生成");
  setFill(sheet, "H1:J3", colors.system);
  setText(sheet, "F5", "请填写项目名称");
  setText(sheet, "F6", "请填写交付地点");
  setText(sheet, "F7", "导入后在系统确认");
  setFill(sheet, "B5:D6", colors.input);
  setFill(sheet, "F5:J6", colors.input);
  setFill(sheet, "B7:D7", colors.system);
  setFill(sheet, "F7:J7", colors.system);
  setText(sheet, "H9", "材料单价（系统）");
  setText(sheet, "I9", "材料金额（系统）");
}

function prepareInstallation() {
  const sheet = workbook.worksheets.getItem("带安装");
  prepareDataRows(sheet, "L");
  styleCommon(sheet, "L", "H10:K29", "L10:L29");
  setText(sheet, "C1", "材 料 及 安 装 报 价 输 入 单");
  setText(sheet, "I1", "系统生成");
  setText(sheet, "I2", "系统生成");
  setText(sheet, "I3", "系统生成");
  setFill(sheet, "H1:L3", colors.system);
  setText(sheet, "G5", "请填写项目名称");
  setText(sheet, "G6", "请填写交付地点");
  setText(sheet, "G7", "导入后在系统确认");
  setFill(sheet, "B5:E6", colors.input);
  setFill(sheet, "G5:L6", colors.input);
  setFill(sheet, "B7:E7", colors.system);
  setFill(sheet, "G7:L7", colors.system);
  setText(sheet, "H9", "材料单价（系统）");
  setText(sheet, "I9", "材料金额（系统）");
  setText(sheet, "J9", "安装单价（系统）");
  setText(sheet, "K9", "安装金额（系统）");
}

prepareSingleMaterial();
prepareInstallation();

workbook.recalculate();

const overview = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 18000,
  tableMaxRows: 12,
  tableMaxCols: 14,
  tableMaxCellChars: 100,
});
console.log(overview.ndjson);

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
});
console.log(errors.ndjson);

await fs.mkdir(previewDir, { recursive: true });
for (const [name, range, file] of [
  ["单层材料", "A1:J30", "单层材料.png"],
  ["带安装", "A1:L30", "带安装.png"],
]) {
  const preview = await workbook.render({ sheetName: name, range, scale: 1.2, format: "png" });
  await fs.writeFile(`${previewDir}/${file}`, new Uint8Array(await preview.arrayBuffer()));
}

await fs.mkdir(outputDir, { recursive: true });
const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);

const saved = await SpreadsheetFile.importXlsx(await FileBlob.load(outputPath));
const savedCheck = await saved.inspect({
  kind: "sheet,table",
  maxChars: 12000,
  tableMaxRows: 12,
  tableMaxCols: 14,
  tableMaxCellChars: 100,
});
console.log(savedCheck.ndjson);
console.log(JSON.stringify({ outputPath }));
