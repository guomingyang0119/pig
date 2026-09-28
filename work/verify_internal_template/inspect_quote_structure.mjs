import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "D:/pig/pig-upms/pig-upms-biz/src/main/resources/quotation/quotation-template.xlsx";
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));

for (const name of ["单层材料", "带安装"]) {
  const sheet = workbook.worksheets.getItem(name);
  const formulas = await workbook.inspect({
    kind: "formula",
    sheetId: name,
    range: name === "单层材料" ? "A1:J23" : "A1:L24",
    maxChars: 12000,
    options: { maxResults: 100 },
  });
  console.log(`---${name} formulas---`);
  console.log(formulas.ndjson);

  const styles = await workbook.inspect({
    kind: "computedStyle",
    sheetId: name,
    range: name === "单层材料" ? "A5:J16" : "A5:L17",
    maxChars: 10000,
  });
  console.log(`---${name} styles---`);
  console.log(styles.ndjson);
}
