import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const input = await FileBlob.load("D:/pig/quotation-verification/test-output/报价校对版-预览.xlsx");
const workbook = await SpreadsheetFile.importXlsx(input);
const inspection = await workbook.inspect({ kind: "workbook,sheet,table", tableMaxRows: 20, tableMaxCols: 12, maxChars: 12000 });
console.log(inspection.ndjson);
const preview = await workbook.render({ sheetName: "报价校对版", autoCrop: "all", scale: 1, format: "png" });
await fs.writeFile("D:/pig/quotation-verification/test-output/报价校对版-预览.png", new Uint8Array(await preview.arrayBuffer()));
