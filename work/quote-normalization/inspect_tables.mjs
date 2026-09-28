import { FileBlob, SpreadsheetFile } from '@oai/artifact-tool';
const wb=await SpreadsheetFile.importXlsx(await FileBlob.load('C:/Users/HONOR/Desktop/统一报价输入模板-20260926.xlsx'));
for(const s of wb.worksheets.items){ console.log(s.name, s.tables.items.map(t=>({name:t.name,range:t.range?.address,showHeaders:t.showHeaders,style:t.style}))); }
