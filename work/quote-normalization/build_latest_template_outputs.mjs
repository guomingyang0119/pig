import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const workDir = "D:/pig/work/quote-normalization";
const outputDir = "D:/pig/outputs/01a0e0b5-9334-7b43-8a43-3c56deacc813/真实客户清单整理-最新版模板-20260927";
const templatePath = "C:/Users/HONOR/Desktop/统一报价输入模板-20260926.xlsx";
const data = JSON.parse(await fs.readFile(`${workDir}/sources.json`, "utf8"));
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
  if (item.file.startsWith("梧州光荣项目") && item.sourceRow < 128) return { status: "pending", reason: "位于消防给排水、电气等非通风段，避免误套通风报价规则" };
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
    if (!areaUnits.has(item.unit)) return { status: "pending", reason: "该行虽含‘风管’字样，但不是按平方米计价的风管材料" };
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

function setProjectFields(workbook, projectName) {
  const sheet = workbook.worksheets.getItem("单层材料");
  sheet.getRange("E5").values = [["工程名称"]];
  sheet.getRange("F5").values = [[projectName]];
}

function prepareSingleMaterialSheet(sheet, rows) {
  const dataStart = 10;
  const dataEnd = dataStart + rows.length - 1;
  const footerRow = dataEnd + 1;

  sheet.unmergeCells("A30:J30");
  sheet.getRange("A10:J250").clear({ applyTo: "contents" });
  for (let row = dataStart; row <= dataEnd; row++) {
    sheet.getRange(`A${row}:J${row}`).copyFrom(sheet.getRange("A10:J10"), "all");
  }
  sheet.getRange(`A${dataStart}:J${dataEnd}`).values = rows.map((item, index) => [
    index + 1,
    item.name,
    numberValue(item.thickness),
    item.spec,
    item.flange,
    item.unit,
    numberValue(item.quantity),
    "",
    "",
    "",
  ]);
  sheet.getRange(`A${dataStart}:J${dataEnd}`).format.rowHeight = 34;
  sheet.getRange(`B${dataStart}:F${dataEnd}`).format.wrapText = true;
  sheet.getRange(`J${dataStart}:J${dataEnd}`).format.wrapText = true;
  sheet.getRange(`C${dataStart}:C${dataEnd}`).format.numberFormat = "0.###";
  sheet.getRange(`G${dataStart}:G${dataEnd}`).format.numberFormat = "0.###";

  sheet.getRange(`A${footerRow}:J${footerRow}`).clear({ applyTo: "all" });
  sheet.mergeCells(`A${footerRow}:J${footerRow}`);
  sheet.getRange(`A${footerRow}`).values = [["报价说明：两个工作表任选其一填写；产品名称、规格型号、法兰方式、单位和数量请尽量填写完整。价格、金额、合计、报价编号及商务条款由系统生成。"]];
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.fill = "#EAF2F8";
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.font = { name: "Microsoft YaHei", size: 9, color: "#2D3338" };
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.wrapText = true;
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.verticalAlignment = "center";
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.borders = { preset: "outside", style: "thin", color: "#AEB7BF" };
  sheet.getRange(`A${footerRow}:J${footerRow}`).format.rowHeight = 38;
  return { dataEnd, footerRow };
}

async function buildOne(result, filename, projectName) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(templatePath));
  setProjectFields(workbook, projectName);
  const materialSheet = workbook.worksheets.getItem("单层材料");
  const range = prepareSingleMaterialSheet(materialSheet, result.supported);
  workbook.worksheets.getItem("带安装").delete();

  workbook.recalculate();
  const inspection = await workbook.inspect({
    kind: "table",
    range: `单层材料!A1:J${Math.min(range.footerRow, 22)}`,
    include: "values,formulas",
    tableMaxRows: 22,
    tableMaxCols: 10,
    maxChars: 12000,
  });
  await fs.writeFile(`${workDir}/${filename}.latest.inspection.ndjson`, inspection.ndjson, "utf8");
  const errors = await workbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
    options: { useRegex: true, maxResults: 100 },
    summary: "formula error scan",
  });
  await fs.writeFile(`${workDir}/${filename}.latest.errors.ndjson`, errors.ndjson, "utf8");

  for (const sheet of workbook.worksheets.items) {
    const renderRange = sheet.name === "单层材料" ? `A1:J${Math.min(range.footerRow, 28)}` : "A1:L30";
    const preview = await workbook.render({ sheetName: sheet.name, range: renderRange, scale: 0.75, format: "png" });
    await fs.writeFile(`${workDir}/${filename}-${sheet.name}.latest.png`, new Uint8Array(await preview.arrayBuffer()));
  }

  const output = await SpreadsheetFile.exportXlsx(workbook);
  const outputPath = `${outputDir}/${filename}.xlsx`;
  await output.save(outputPath);
  return outputPath;
}

await fs.mkdir(outputDir, { recursive: true });
const results = collect();
const jobs = [
  ["马板变电站通风报价单.xlsx", "01-马板变电站-统一报价输入", "马板变电站通风工程"],
  ["暖通询价单.xls", "02-暖通询价单-统一报价输入", "暖通工程"],
  ["皮洛遗址保护利用项目-暖通.xlsx", "03-皮洛遗址保护利用项目-统一报价输入", "皮洛遗址保护利用项目暖通工程"],
  ["梧州光荣项目辉业 9.11.xls", "04-梧州光荣项目-统一报价输入", "梧州光荣项目防排烟工程"],
  ["正安报价(1)1.xlsx", "05-正安项目-统一报价输入", "正安项目通风工程"],
];
const outputs = [];
for (const [source, filename, projectName] of jobs) {
  const result = results.find((item) => item.file.filename === source);
  outputs.push(await buildOne(result, filename, projectName));
}
const summary = {
  template: templatePath,
  outputDir,
  outputs,
  counts: results.map((result) => ({ file: result.file.filename, supported: result.supported.length, pending: result.pending.length })),
};
await fs.writeFile(`${workDir}/latest-template-summary.json`, JSON.stringify(summary, null, 2), "utf8");
console.log(JSON.stringify(summary, null, 2));
