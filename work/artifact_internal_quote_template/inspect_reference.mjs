import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "D:/pig/quotation-verification/reference/assets/正式报价模板_双模板最终精简版_20260922.xlsx";
const previewDir = "D:/pig/work/artifact_internal_quote_template/reference-previews";
const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

await fs.mkdir(previewDir, { recursive: true });
const summary = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 12000,
  tableMaxRows: 12,
  tableMaxCols: 20,
  tableMaxCellChars: 100,
});
console.log(summary.ndjson);

for (let index = 0; index < workbook.worksheets.items.length; index += 1) {
  const sheet = workbook.worksheets.getItemAt(index);
  const used = sheet.getUsedRange();
  const range = used ? used.address : "A1:N30";
  const region = await workbook.inspect({
    kind: "region",
    sheetId: sheet.name,
    range,
    maxChars: 16000,
    tableMaxRows: 25,
    tableMaxCols: 20,
    tableMaxCellChars: 120,
  });
  console.log(region.ndjson);
  const preview = await workbook.render({ sheetName: sheet.name, autoCrop: "all", scale: 1, format: "png" });
  const safeName = sheet.name.replace(/[\\/:*?"<>|]/g, "_");
  await fs.writeFile(`${previewDir}/${index + 1}-${safeName}.png`, new Uint8Array(await preview.arrayBuffer()));
}
