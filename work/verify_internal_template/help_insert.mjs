import { Workbook } from "@oai/artifact-tool";

const workbook = Workbook.create();
console.log(workbook.help("range.insert", { include: "index,examples,notes", maxChars: 4000 }).ndjson);
