import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputs = [
  "C:/Users/HONOR/Desktop/客户报价清单/马板变电站通风报价单.xlsx",
  "C:/Users/HONOR/Desktop/客户报价清单/正安报价(1)1.xlsx",
];
const previewDir = "D:/pig/work/artifact_internal_quote_template/customer-previews";
await fs.mkdir(previewDir, { recursive: true });

for (const inputPath of inputs) {
  const input = await FileBlob.load(inputPath);
  const workbook = await SpreadsheetFile.importXlsx(input);
  const base = path.basename(inputPath, path.extname(inputPath));
  const summary = await workbook.inspect({
    kind: "workbook,sheet,table",
    maxChars: 16000,
    tableMaxRows: 18,
    tableMaxCols: 18,
    tableMaxCellChars: 120,
  });
  console.log(`\n=== ${base} ===`);
  console.log(summary.ndjson);
  for (let index = 0; index < workbook.worksheets.items.length; index += 1) {
    const sheet = workbook.worksheets.getItemAt(index);
    const used = sheet.getUsedRange();
    if (!used) continue;
    const preview = await workbook.render({ sheetName: sheet.name, autoCrop: "all", scale: 0.8, format: "png" });
    const safeSheet = sheet.name.replace(/[\\/:*?"<>|]/g, "_");
    await fs.writeFile(`${previewDir}/${base}-${index + 1}-${safeSheet}.png`, new Uint8Array(await preview.arrayBuffer()));
  }
}
