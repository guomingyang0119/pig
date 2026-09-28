import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const input = "C:/Users/HONOR/Desktop/统一报价输入模板-20260926.xlsx";
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(input));
const result = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 18000,
  tableMaxRows: 34,
  tableMaxCols: 14,
  tableMaxCellChars: 120,
});
console.log(result.ndjson);
for (const sheet of workbook.worksheets.items) {
  const preview = await workbook.render({ sheetName: sheet.name, autoCrop: "all", scale: 0.8, format: "png" });
  await fs.writeFile(`D:/pig/work/quote-normalization/desktop-template-${sheet.name}.png`, new Uint8Array(await preview.arrayBuffer()));
}
