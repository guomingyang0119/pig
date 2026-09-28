import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const workDir = "D:/pig/work/quote-normalization";
const outputDir = "D:/pig/outputs/01a0e0b5-9334-7b43-8a43-3c56deacc813/真实客户清单整理-20260927";
const data = JSON.parse(await fs.readFile(`${workDir}/sources.json`, "utf8"));
const font = "Microsoft YaHei";
const allowedUnits = new Set(["㎡", "台", "个", "只", "套", "件", "组", "樘"]);
const areaUnits = new Set(["㎡", "m2", "m²", "平方米"]);

function clean(value) {
  return value == null ? "" : String(value).replace(/\r/g, "").replace(/\s*\n\s*/g, "；").replace(/；{2,}/g, "；").trim();
}

function numberValue(value) {
  const text = clean(value).replace(/,/g, "");
  if (!text || !/^-?\d+(?:\.\d+)?$/.test(text)) return text;
  return Number(text);
}

function normalizeUnit(value) {
  const unit = clean(value);
  if (["m2", "m²", "平方米"].includes(unit)) return "㎡";
  if (["m", "meter", "metre"].includes(unit.toLowerCase())) return "米";
  if (unit === "节") return "个";
  return unit;
}

function extractDimensions(text) {
  const raw = clean(text);
  const rectangular = raw.match(/(\d+(?:\.\d+)?)\s*[×xX*＊]\s*(\d+(?:\.\d+)?)(?:\s*[×xX*＊]\s*(\d+(?:\.\d+)?))?/);
  if (rectangular) return { width: rectangular[1], height: rectangular[2], length: rectangular[3] || "", text: rectangular[0] };
  const round = raw.match(/(?:[ΦφØø]|DN|直径\s*[:：=]?)\s*(\d+(?:\.\d+)?)/i);
  if (round) return { width: round[1], height: round[1], length: "", text: round[0] };
  return { width: "", height: "", length: "", text: "" };
}

function extractThickness(text) {
  const raw = clean(text);
  const match = raw.match(/(?:δ\s*=\s*|厚(?:度)?\s*[:：=]?\s*)?(0?\.\d+|[1-9]\d*(?:\.\d+)?)\s*mm/i);
  return match ? match[1] : "";
}

function extractFanData(text, parsed = {}) {
  const raw = clean(text);
  const airflow = raw.match(/(?:风量\s*(?:(?:m3|m³)\s*\/\s*h)?|(?<![A-Z])(?:Q|L))\s*[:：=＝]?\s*(\d+(?:\.\d+)?)/i)?.[1] || clean(parsed.airflow);
  const pressure = raw.match(/(?:全压|静压|风压)\s*[:：=＝]?\s*(?:\(\s*Pa\s*\))?\s*[:：=＝]?\s*(\d+(?:\.\d+)?)|(?<![A-Z0-9/])(?:P|H)\s*[:：=＝]\s*(\d+(?:\.\d+)?)/i)?.slice(1).find(Boolean) || clean(parsed.pressure);
  const power = raw.match(/功率\s*(?:\(\s*kW\s*\))?\s*[:：=＝]?\s*(\d+(?:\.\d+)?)|(?:P|N)\s*[:：=＝]\s*(\d+(?:\.\d+)?)\s*(?:kW|W)/i)?.slice(1).find(Boolean) || "";
  return { airflow, pressure, power };
}

function isIgnorable(name) {
  const value = clean(name).replace(/\s/g, "");
  return !value || /^(小计|合计|总计|运费|税金|备注|说明)$/.test(value);
}

function baseRow(file, row) {
  let name = clean(row.name);
  let spec = clean(row.spec);
  let category = clean(row.category);
  let unit = normalizeUnit(row.unit);
  const quantity = clean(row.quantity) || clean(row.engineering_quantity);
  const parsed = { ...(row.parsed_attributes || {}) };
  let alias = "";

  if (!spec && row.attributes?.model) spec = clean(row.attributes.model);
  if (name.includes("管道式排气扇")) {
    alias = name;
    name = "管道风机";
    category = "风机";
  }
  if (file.filename === "暖通询价单.xls" && name.includes("蝶阀")) {
    alias = alias || name;
    name = "手动调节阀（蝶阀）";
    category = "风阀";
  }
  if (file.filename.startsWith("梧州光荣项目") && row.source_row >= 129 && row.source_row <= 133) {
    alias = alias || name;
    if (!spec) spec = name;
    name = name.includes("不锈钢") ? "304不锈钢板风管" : "镀锌钢板风管";
    category = "风管";
    unit = "㎡";
  }

  const dimensions = extractDimensions(`${name} ${spec}`);
  if (dimensions.text && !spec) spec = dimensions.text;
  if (dimensions.text && name.endsWith(dimensions.text) && category !== "风机") {
    const originalName = name;
    name = name.slice(0, -dimensions.text.length).trim();
    if (!spec || spec === originalName) spec = dimensions.text;
  }
  const thickness = clean(row.attributes?.thickness) || clean(parsed.thickness) || extractThickness(`${name} ${spec}`);
  const flange = /共板法兰/.test(`${name} ${spec}`) ? "共板法兰" : /角钢法兰/.test(`${name} ${spec}`) ? "角钢法兰" : /圆法兰/.test(`${name} ${spec}`) ? "圆法兰" : "";
  const source = `${clean(row.source_sheet) || "工作表"}第${row.source_row}行`;
  const originalRemark = clean(row.remark);
  const remarks = [alias ? `原名称：${alias}` : "", originalRemark ? `原备注：${originalRemark}` : "", `来源：${source}`].filter(Boolean).join("；");
  return { file: file.filename, sourceSheet: clean(row.source_sheet), sourceRow: row.source_row, name, spec, category, unit, quantity, thickness, flange, remarks, parsed, dimensions, raw: row };
}

function decision(item) {
  const all = `${item.name} ${item.spec} ${item.remarks}`;
  if (isIgnorable(item.name)) return { status: "skip", reason: "" };
  if (item.file.startsWith("梧州光荣项目") && item.sourceRow < 128) {
    return { status: "pending", reason: "位于消防给排水、电气等非通风段，避免误套通风报价规则" };
  }
  if (!item.quantity || !(Number(String(item.quantity).replace(/,/g, "")) > 0)) return { status: "pending", reason: "缺少有效数量或工程量" };
  if (!item.category) return { status: "pending", reason: "现有规则无法识别产品类型，需补充名称映射或人工明确类型" };
  if (!item.unit) return { status: "pending", reason: "缺少计价单位" };

  if (item.category === "风机") {
    const fan = extractFanData(all, item.parsed);
    if (!fan.airflow || !fan.pressure) return { status: "pending", reason: "风机选型缺少风量或风压，当前规则无法完成选型" };
    if (!allowedUnits.has(item.unit)) return { status: "pending", reason: "风机单位不是台、个、只、套、件、组或樘" };
    if (clean(item.parsed.airflow) !== fan.airflow || clean(item.parsed.pressure) !== fan.pressure) {
      const originalSpec = item.spec;
      item.spec = `风量：${fan.airflow}m³/h，风压：${fan.pressure}Pa${fan.power ? `，功率：${fan.power}kW` : ""}`;
      if (originalSpec) item.remarks = `原规格已标准化，详见来源行；${item.remarks}`;
    }
    return { status: "supported", reason: "" };
  }
  if (item.category === "风阀") {
    if (!item.dimensions.width || !item.dimensions.height) return { status: "pending", reason: "风阀缺少宽×高或直径规格" };
    if (!allowedUnits.has(item.unit)) return { status: "pending", reason: "风阀单位不是台、个、只、套、件、组或樘" };
    return { status: "supported", reason: "" };
  }
  if (item.category === "百叶风口") {
    if (!item.dimensions.width || !item.dimensions.height) return { status: "pending", reason: "风口缺少宽×高或直径规格" };
    if (!/(散流|防雨|自垂|门铰|检修|条缝|线形|双层|单层)/.test(all)) return { status: "pending", reason: "风口未明确单层、双层、散流器、防雨等具体类型" };
    if (all.includes("阀") && all.includes("网")) return { status: "pending", reason: "风口同时带阀和网，当前规则要求人工确认组合成本" };
    if (!allowedUnits.has(item.unit)) return { status: "pending", reason: "风口单位不是台、个、只、套、件、组或樘" };
    return { status: "supported", reason: "" };
  }
  if (item.category === "消声设备") {
    if (/消声弯头|消音弯头/.test(all)) return { status: "pending", reason: "消声弯头缺少每个的人工计算面积/方量" };
    if (!item.dimensions.width || !item.dimensions.height || !item.dimensions.length) return { status: "pending", reason: "消声器或静压箱缺少宽×高×长三维规格" };
    if (!allowedUnits.has(item.unit)) return { status: "pending", reason: "消声设备单位不符合现有计价规则" };
    return { status: "supported", reason: "" };
  }
  if (item.category === "外购设备") return { status: "pending", reason: "外购设备必须与已发布成本目录中的唯一型号匹配，单凭清单无法确认" };
  if (item.category === "风管") {
    if (!areaUnits.has(item.unit)) return { status: "pending", reason: "该行虽含“风管”字样，但不是按平方米计价的风管材料" };
    if (!item.thickness) return { status: "pending", reason: "风管缺少板材厚度" };
    return { status: "supported", reason: "" };
  }
  return { status: "pending", reason: "产品类型不在当前报价规则范围内" };
}

function collect() {
  const results = [];
  for (const file of data) {
    const supported = [];
    const pending = [];
    if (file.built?.rows) {
      for (const row of file.built.rows) {
        const item = baseRow(file, row);
        const result = decision(item);
        if (result.status === "supported") supported.push(item);
        else if (result.status === "pending") pending.push({ ...item, reason: result.reason });
      }
    } else if (file.filename.startsWith("睿亿泰净化医疗")) {
      const sheet = file.sheets?.[0];
      for (const row of sheet?.rows || []) {
        const no = Number(row.number);
        if (no < 14 || no > 19) continue;
        const cells = row.cells || [];
        pending.push({ file: file.filename, sourceSheet: sheet.name, sourceRow: no, name: clean(cells[0]), spec: clean(cells[3]), unit: "", quantity: "", category: "风管", reason: "这是材料单价合同，缺少工程量和计价单位，不能直接作为工程订单导入" });
      }
    }
    results.push({ file, supported, pending });
  }
  return results;
}

function styleTemplateSheet(sheet, projectName, rowCount) {
  const dataStart = 10;
  const dataEnd = Math.max(309, 9 + rowCount);
  sheet.showGridLines = false;
  sheet.tabColor = "#5B6573";
  sheet.getRange(`A1:I${dataEnd}`).format.font = { name: font, size: 10, color: "#2F343B" };
  sheet.mergeCells("A1:G2");
  sheet.getRange("A1").values = [["内 部 综 合 工 程 清 单"]];
  sheet.getRange("A1:G2").format.font = { name: font, size: 20, bold: true, color: "#2F343B" };
  sheet.getRange("A1:G2").format.horizontalAlignment = "center";
  sheet.getRange("A1:G2").format.verticalAlignment = "center";
  sheet.mergeCells("A3:G3");
  sheet.getRange("A3").values = [["贵州科庆通风设备有限公司"]];
  sheet.getRange("A3:G3").format.font = { name: font, size: 11, bold: true, color: "#2F343B" };
  sheet.getRange("A3:G3").format.horizontalAlignment = "center";
  sheet.getRange("H1:I3").values = [["模板用途", "报价系统导入"], ["整理日期", "2026-09-27"], ["整单选项", "导入后在系统确认"]];
  sheet.getRange("H1:H3").format.fill = "#F2F3F5";
  sheet.getRange("H1:H3").format.font = { name: font, size: 9, bold: true, color: "#2F343B" };
  sheet.getRange("H1:I3").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
  sheet.getRange("H1:I3").format.verticalAlignment = "center";
  sheet.getRange("H1:I3").format.horizontalAlignment = "center";
  sheet.getRange("I2").format.fill = "#FFF7DC";
  sheet.getRange("A4:I4").format.rowHeight = 8;
  sheet.getRange("A5:I6").values = [["客户名称", "", "", "", "工程名称", projectName, "", "", ""], ["联系人", "", "", "", "交付地点", "", "", "", ""]];
  sheet.mergeCells("B5:D5"); sheet.mergeCells("F5:I5"); sheet.mergeCells("B6:D6"); sheet.mergeCells("F6:I6");
  for (const address of ["A5", "E5", "A6", "E6"]) {
    sheet.getRange(address).format.fill = "#F2F3F5";
    sheet.getRange(address).format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
    sheet.getRange(address).format.horizontalAlignment = "center";
  }
  sheet.getRange("A5:I6").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
  sheet.getRange("B5:D6").format.fill = "#FFF7DC";
  sheet.getRange("F5:I6").format.fill = "#FFF7DC";
  sheet.getRange("A5:I6").format.verticalAlignment = "center";
  sheet.getRange("A7").values = [["填写说明"]]; sheet.mergeCells("B7:I7");
  sheet.getRange("B7").values = [["本表已从客户原始资料中筛出当前规则可处理的行，并保留来源行号。客户名称、联系人、交付地点及整单安装、运费、税率等选项请在导入前后按实际情况补充确认。"]];
  sheet.getRange("A7").format.fill = "#F2F3F5";
  sheet.getRange("A7").format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
  sheet.getRange("A7").format.horizontalAlignment = "center";
  sheet.getRange("B7:I7").format.fill = "#FFF7DC";
  sheet.getRange("B7:I7").format.font = { name: font, size: 9, color: "#5D636B" };
  sheet.getRange("A7:I7").format.borders = { preset: "all", style: "thin", color: "#D4D8DE" };
  sheet.getRange("A7:I7").format.verticalAlignment = "center";
  sheet.getRange("B7:I7").format.wrapText = true;
  sheet.mergeCells("A8:I8"); sheet.getRange("A8").values = [["清单明细"]];
  sheet.getRange("A8:I8").format.font = { name: font, size: 11, bold: true, color: "#2F343B" };
  sheet.getRange("A8:I8").format.verticalAlignment = "center";
  sheet.getRange("A8:I8").format.borders = { bottom: { style: "medium", color: "#707780" } };
  const headers = ["序号", "产品名称", "产品类型", "规格型号", "厚度(mm)", "法兰方式", "单位", "数量", "备注"];
  sheet.getRange("A9:I9").values = [headers];
  sheet.getRange(`A${dataStart}:I${dataEnd}`).values = Array.from({ length: dataEnd - dataStart + 1 }, () => Array(9).fill(""));
  const table = sheet.tables.add(`A9:I${dataEnd}`, true, "ProjectItems");
  table.style = "TableStyleLight9"; table.showFilterButton = true; table.showBandedRows = true;
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
  sheet.getRange(`C${dataStart}:C${dataEnd}`).dataValidation = { rule: { type: "list", values: ["风管", "风机", "风阀", "百叶风口", "消声设备", "外购设备", "其他材料"] } };
  sheet.getRange(`F${dataStart}:F${dataEnd}`).dataValidation = { rule: { type: "list", values: ["共板法兰", "角钢法兰", "圆法兰"] } };
  sheet.getRange(`G${dataStart}:G${dataEnd}`).dataValidation = { rule: { type: "list", values: ["㎡", "台", "个", "只", "套", "件", "组", "樘", "米", "m", "m³", "项"] } };
  const widths = { A: 7, B: 25, C: 13, D: 34, E: 12, F: 14, G: 10, H: 12, I: 31 };
  for (const [column, width] of Object.entries(widths)) sheet.getRange(`${column}:${column}`).format.columnWidth = width;
  sheet.getRange("A1:I1").format.rowHeight = 26; sheet.getRange("A2:I2").format.rowHeight = 26; sheet.getRange("A3:I3").format.rowHeight = 22;
  sheet.getRange("A5:I6").format.rowHeight = 23; sheet.getRange("A7:I7").format.rowHeight = 44; sheet.getRange("A8:I8").format.rowHeight = 24;
  sheet.getRange("A9:I9").format.rowHeight = 30; sheet.getRange(`A${dataStart}:I${dataEnd}`).format.rowHeight = 42;
  sheet.freezePanes.freezeRows(9); sheet.freezePanes.freezeColumns(2);
}

async function writeImportWorkbook(result, filename, projectName) {
  const workbook = Workbook.create();
  const sheet = workbook.worksheets.add("工程清单");
  styleTemplateSheet(sheet, projectName, result.supported.length);
  const rows = result.supported.map((item, index) => [index + 1, item.name, item.category, item.spec, numberValue(item.thickness), item.flange, item.unit, numberValue(item.quantity), item.remarks]);
  if (rows.length) sheet.getRange(`A10:I${9 + rows.length}`).values = rows;
  workbook.recalculate();
  const inspection = await workbook.inspect({ kind: "table", range: `工程清单!A1:I${Math.min(20, 9 + rows.length)}`, include: "values,formulas", tableMaxRows: 20, tableMaxCols: 9, maxChars: 9000 });
  await fs.writeFile(`${workDir}/${filename}.inspection.ndjson`, inspection.ndjson, "utf8");
  const errors = await workbook.inspect({ kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!", options: { useRegex: true, maxResults: 100 }, summary: "formula error scan" });
  await fs.writeFile(`${workDir}/${filename}.errors.ndjson`, errors.ndjson, "utf8");
  const preview = await workbook.render({ sheetName: "工程清单", range: `A1:I${Math.min(28, 9 + rows.length)}`, scale: 0.8, format: "png" });
  await fs.writeFile(`${workDir}/${filename}.png`, new Uint8Array(await preview.arrayBuffer()));
  const output = await SpreadsheetFile.exportXlsx(workbook);
  const outputPath = `${outputDir}/${filename}.xlsx`;
  await output.save(outputPath);
  return outputPath;
}

function styleAuditSheet(sheet, title, headers, rows, widths) {
  const end = Math.max(4, 3 + rows.length);
  sheet.showGridLines = false;
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}${end}`).format.font = { name: font, size: 10, color: "#2F343B" };
  sheet.mergeCells(`A1:${String.fromCharCode(64 + headers.length)}1`);
  sheet.getRange("A1").values = [[title]];
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}1`).format.font = { name: font, size: 16, bold: true, color: "#2F343B" };
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}1`).format.horizontalAlignment = "center";
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}1`).format.rowHeight = 30;
  sheet.getRange("A3").resize(1, headers.length).values = [headers];
  if (rows.length) sheet.getRange("A4").resize(rows.length, headers.length).values = rows;
  const table = sheet.tables.add(`A3:${String.fromCharCode(64 + headers.length)}${end}`, true, `Audit${sheet.name === "整理汇总" ? "Summary" : "Pending"}`);
  table.style = "TableStyleLight9"; table.showBandedRows = true; table.showFilterButton = true;
  sheet.getRange(`A3:${String.fromCharCode(64 + headers.length)}3`).format.fill = "#EFF1F3";
  sheet.getRange(`A3:${String.fromCharCode(64 + headers.length)}3`).format.font = { name: font, size: 10, bold: true, color: "#2F343B" };
  sheet.getRange(`A3:${String.fromCharCode(64 + headers.length)}${end}`).format.borders = { preset: "all", style: "thin", color: "#D9DDE2" };
  sheet.getRange(`A4:${String.fromCharCode(64 + headers.length)}${end}`).format.verticalAlignment = "center";
  sheet.getRange(`A4:${String.fromCharCode(64 + headers.length)}${end}`).format.wrapText = true;
  for (let i = 0; i < widths.length; i++) sheet.getRange(`${String.fromCharCode(65 + i)}:${String.fromCharCode(65 + i)}`).format.columnWidth = widths[i];
  sheet.getRange(`A4:${String.fromCharCode(64 + headers.length)}${end}`).format.rowHeight = 48;
  sheet.freezePanes.freezeRows(3);
}

async function writeAuditWorkbook(results) {
  const workbook = Workbook.create();
  const summary = workbook.worksheets.add("整理汇总");
  const summaryRows = results.map((result) => [result.file.filename, result.supported.length, result.pending.length, result.supported.length ? "已生成统一导入清单" : "未生成导入清单", result.file.filename.startsWith("睿亿泰净化医疗") ? "材料单价合同，不是含工程量的订单" : "待补项详见下一页"]);
  styleAuditSheet(summary, "真实客户清单整理结果", ["原文件", "可导入行数", "待补行数", "处理结果", "说明"], summaryRows, [38, 13, 13, 20, 38]);
  const pendingSheet = workbook.worksheets.add("待补项");
  const pendingRows = results.flatMap((result) => result.pending.map((item) => [item.file, item.sourceSheet || "", item.sourceRow || "", item.name, item.category || "未识别", item.spec || "", item.unit || "", numberValue(item.quantity), item.reason]));
  styleAuditSheet(pendingSheet, "暂不能由当前规则直接报价的项目", ["原文件", "工作表", "原行号", "产品名称", "识别类型", "规格型号", "单位", "数量", "待补原因"], pendingRows, [34, 18, 9, 28, 13, 32, 9, 10, 42]);
  workbook.recalculate();
  for (const sheet of [summary, pendingSheet]) {
    const preview = await workbook.render({ sheetName: sheet.name, range: sheet.name === "整理汇总" ? "A1:E10" : `A1:I${Math.min(30, 3 + pendingRows.length)}`, scale: 0.75, format: "png" });
    await fs.writeFile(`${workDir}/整理结果与待补项-${sheet.name}.png`, new Uint8Array(await preview.arrayBuffer()));
  }
  const inspection = await workbook.inspect({ kind: "table", range: "整理汇总!A1:E10", include: "values,formulas", tableMaxRows: 12, tableMaxCols: 5, maxChars: 8000 });
  await fs.writeFile(`${workDir}/整理结果与待补项.inspection.ndjson`, inspection.ndjson, "utf8");
  const errors = await workbook.inspect({ kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!", options: { useRegex: true, maxResults: 100 }, summary: "formula error scan" });
  await fs.writeFile(`${workDir}/整理结果与待补项.errors.ndjson`, errors.ndjson, "utf8");
  const output = await SpreadsheetFile.exportXlsx(workbook);
  const outputPath = `${outputDir}/整理结果与待补项.xlsx`;
  await output.save(outputPath);
  return outputPath;
}

await fs.mkdir(outputDir, { recursive: true });
const results = collect();
const names = [
  ["马板变电站通风报价单.xlsx", "01-马板变电站-统一输入清单", "马板变电站通风工程"],
  ["暖通询价单.xls", "02-暖通询价单-统一输入清单", "暖通工程"],
  ["皮洛遗址保护利用项目-暖通.xlsx", "03-皮洛遗址保护利用项目-统一输入清单", "皮洛遗址保护利用项目暖通工程"],
  ["梧州光荣项目辉业 9.11.xls", "04-梧州光荣项目-统一输入清单", "梧州光荣项目防排烟工程"],
  ["正安报价(1)1.xlsx", "05-正安项目-统一输入清单", "正安项目通风工程"],
];
const outputs = [];
for (const [source, filename, project] of names) {
  const result = results.find((item) => item.file.filename === source);
  outputs.push(await writeImportWorkbook(result, filename, project));
}
outputs.push(await writeAuditWorkbook(results));
await fs.writeFile(`${workDir}/normalization-summary.json`, JSON.stringify(results.map((r) => ({ file: r.file.filename, supported: r.supported.length, pending: r.pending.length })), null, 2), "utf8");
console.log(JSON.stringify({ outputDir, outputs, counts: results.map((r) => ({ file: r.file.filename, supported: r.supported.length, pending: r.pending.length })) }, null, 2));
