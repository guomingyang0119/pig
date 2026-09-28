import com.pig4cloud.pig.admin.quotation.DuctImportService;
import com.pig4cloud.pig.admin.quotation.EquipmentPricingEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtractQuotes {
	private static String json(Object value) {
		if (value == null) return "null";
		if (value instanceof Boolean || value instanceof Number) return value.toString();
		if (value instanceof Map<?, ?> map) {
			var parts = new ArrayList<String>();
			for (var entry : map.entrySet()) parts.add(json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()));
			return "{" + String.join(",", parts) + "}";
		}
		if (value instanceof Iterable<?> items) {
			var parts = new ArrayList<String>();
			for (var item : items) parts.add(json(item));
			return "[" + String.join(",", parts) + "]";
		}
		String text = String.valueOf(value);
		var out = new StringBuilder("\"");
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			switch (ch) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
					else out.append(ch);
				}
			}
		}
		return out.append('"').toString();
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) throw new IllegalArgumentException("usage: output.json input...");
		var importer = new DuctImportService();
		var results = new ArrayList<Map<String, Object>>();
		for (int i = 1; i < args.length; i++) {
			var path = Path.of(args[i]);
			var item = new LinkedHashMap<String, Object>();
			item.put("path", path.toString());
			item.put("filename", path.getFileName().toString());
			try {
				var sheets = importer.read(Files.readAllBytes(path), path.getFileName().toString());
				item.put("sheets", sheets);
				Map<String, Object> built;
				if (path.getFileName().toString().startsWith("皮洛遗址保护利用项目")) {
					var mapping = new LinkedHashMap<String, Object>();
					mapping.put("name", 1);
					mapping.put("spec", 2);
					mapping.put("unit", 3);
					mapping.put("quantity", 4);
					built = importer.build(sheets.get(0), 4, mapping, "mixed_v1");
					item.put("manual_mapping", mapping);
				} else {
					built = importer.buildWorkbook(sheets, List.of());
				}
				@SuppressWarnings("unchecked")
				var rows = (List<Map<String, Object>>) built.get("rows");
				for (var row : rows) row.put("parsed_attributes", EquipmentPricingEngine.parseAttributes(row));
				item.put("built", built);
			} catch (RuntimeException e) {
				item.put("error", e.getMessage());
			}
			results.add(item);
		}
		Files.writeString(Path.of(args[0]), json(results), StandardCharsets.UTF_8);
	}
}
