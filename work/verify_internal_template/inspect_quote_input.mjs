import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "D:/pig/pig-upms/pig-upms-biz/src/main/resources/quotation/quotation-template.xlsx";
const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const overview = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 30000,
  tableMaxRows: 18,
  tableMaxCols: 15,
  tableMaxCellChars: 100,
});
console.log(overview.ndjson);

const sheets = await workbook.inspect({ kind: "sheet", include: "id,name", maxChars: 3000 });
console.log(sheets.ndjson);

for (let index = 0; index < 2; index += 1) {
  const sheet = workbook.worksheets.getItemAt(index);
  const preview = await workbook.render({
    sheetName: sheet.name,
    range: index === 0 ? "A1:J18" : "A1:L18",
    scale: 1.2,
    format: "png",
  });
  await fs.writeFile(
    `D:/pig/work/verify_internal_template/quote-template-${index + 1}.png`,
    new Uint8Array(await preview.arrayBuffer()),
  );
}
