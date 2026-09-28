import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const [mode, inputPath, outputPath] = process.argv.slice(2);
if (!mode || !inputPath || !outputPath) {
	throw new Error("Usage: node workbook.mjs <render|edit> <input.xlsx> <output>");
}

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);
const sheet = workbook.worksheets.getItem("业务问题对齐");

if (mode === "render") {
	const preview = await workbook.render({ sheetName: sheet.name, autoCrop: "all", scale: 1, format: "png" });
	await fs.writeFile(outputPath, new Uint8Array(await preview.arrayBuffer()));
	process.stdout.write(JSON.stringify({ sheet: sheet.name, preview: outputPath }));
	process.exit(0);
}

if (mode !== "edit") throw new Error(`Unknown mode: ${mode}`);

sheet.getRange("A21:I21").copyFrom(sheet.getRange("A17:I17"), "all");
for (const row of [17, 18, 19, 20]) {
	sheet.getRange(`A${row}:I${row}`).copyFrom(sheet.getRange("A16:I16"), "all");
}

sheet.getRange("A3").values = [[
	"适用测试单：马板变电站通风报价单.xlsx；03-皮洛遗址保护利用项目-统一报价输入.xlsx",
]];

sheet.getRange("A7:I7").values = [[
	1,
	"“防雨小于1.5m”的定义和适用范围",
	"安装指标同时存在“单双层百叶风口；防雨小于1.5m”25元/个和“防雨百叶风口”50元/个。本次测试还有1000×1500、1250×1600等普通双层百叶。",
	"1.5m具体指最长边、宽度、高度、周长还是面积？等于1.5m时归入哪一档？该限制仅适用于防雨百叶，还是也适用于普通单双层百叶？",
	"先确认1.5m的唯一尺寸口径，再确认适用产品范围；无法解析尺寸时保留待确认。",
	"",
	"",
	"待确认",
	"",
]];

sheet.getRange("A10:I10").values = [[
	4,
	"手动及电动多叶调节阀的安装组成",
	"本次测试有10行手动多叶调节阀和3行电动多叶调节阀。当前名称包含“多叶”或“电动”时不自动匹配安装项目。",
	"手动多叶调节阀是否统一按风阀40元/个？电动多叶调节阀是40元、65元，还是40+65=105元？大规格产品是否另行加价？",
	"分别确认手动本体、电动执行机构以及大规格附加费是否叠加。",
	"",
	"",
	"待确认",
	"",
]];

sheet.getRange("A17:I17").values = [[
	11,
	"楼层高度与高空附加费",
	"原清单含0、3.5、8.5、14、19、24、30米层；系统当前只保存一个整单施工高度。",
	"高空附加费按整单一次填写，还是按楼层/产品分别计算？楼层标题是否应自动关联其后的产品行？",
	"A：整单一次；B：逐楼层；C：逐产品。需要明确阈值及金额算法。",
	"",
	"",
	"待确认",
	"",
]];

const additions = [
	[
		12,
		"四川等四地区散流器安装归类",
		"本次测试有1行矩形散流器带阀4面出风和4行可调角度条形散流器，共173个；四川等四地区安装指标没有明确的散流器项目，当前全部待确认。",
		"矩形散流器、条形散流器是否按普通百叶25元/个，还是建立独立安装项目？四面出风、条形等类型是否采用不同价格？",
		"A：统一按百叶25元/个；B：建立散流器独立单价；C：按散流器类型分别定价。",
		"",
		"",
		"待确认",
		"",
	],
	[
		13,
		"带阀风口及散流器的安装组成",
		"本次测试共有10行、194个产品名称带阀。当前带阀双层百叶只按普通百叶25元/个计算，带阀散流器则待确认。",
		"带阀产品只计风口或散流器安装费，还是另加风阀安装费？阀门已包含在成套产品中时是否仍需单独计安装？",
		"A：仅计本体；B：本体+风阀；C：建立成套产品安装价，避免重复收费。",
		"",
		"",
		"待确认",
		"",
	],
	[
		14,
		"大规格风阀安装加价规则",
		"本次防火阀、调节阀和止回阀尺寸从200×200到2500×500、1600×1600不等，当前均按风阀40元/个计算。",
		"大规格或超重风阀是否仍按40元/个？如需加价，按最长边、面积、重量还是规格档位划分，临界值如何处理？",
		"A：全部固定40元/个；B：按尺寸分档；C：超过阈值由报价员填写安装单价。",
		"",
		"",
		"待确认",
		"",
	],
	[
		15,
		"消声器和静压箱安装价格分档",
		"本次有30行、84个消声器或静压箱，尺寸差距较大；当前全部按“消声器/静压箱”300元/个计算。该问题不同于第10项消声弯头。",
		"消声器和静压箱是否不分尺寸统一300元/个？是否需要区分产品类型，并按尺寸、面积或重量设置价格档位？",
		"A：全部固定300元/个；B：消声器与静压箱分别定价；C：按尺寸或重量分档。",
		"",
		"",
		"待确认",
		"",
	],
];

sheet.getRange("A18:I21").values = additions;
sheet.getRange("F7:G21").clear({ applyTo: "contents" });
sheet.getRange("I7:I21").clear({ applyTo: "contents" });
const appendedRows = sheet.getRange("A18:I21");
appendedRows.format.font = { name: "Arial", size: 10, color: "#222222" };
appendedRows.format.fill = "#FFFFFF";
appendedRows.format.verticalAlignment = "top";
appendedRows.format.wrapText = true;
appendedRows.format.borders = { preset: "all", style: "thin", color: "#BFBFBF" };
sheet.getRange("A18:A21").format.horizontalAlignment = "center";
sheet.getRange("G18:H21").format.horizontalAlignment = "center";
sheet.getRange("H18:H21").dataValidation = {
	rule: { type: "list", values: ["待确认", "已确认", "不适用"] },
};
sheet.getRange("A3:I3").format.rowHeight = 30;
sheet.getRange("A7:I7").format.rowHeight = 78;
sheet.getRange("A10:I10").format.rowHeight = 78;
sheet.getRange("A18:I21").format.rowHeight = 78;

await workbook.recalculate();
const check = await workbook.inspect({
	kind: "region",
	sheetId: sheet.name,
	range: "A1:I21",
	maxChars: 8000,
	tableMaxRows: 24,
	tableMaxCols: 9,
});

const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);
const previewPath = outputPath.replace(/\.xlsx$/i, "-预览.png");
const preview = await workbook.render({ sheetName: sheet.name, autoCrop: "all", scale: 1, format: "png" });
await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
process.stdout.write(JSON.stringify({ output: outputPath, preview: previewPath, inspect: check.ndjson }));
