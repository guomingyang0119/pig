import com.pig4cloud.pig.admin.quotation.DuctImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class QuoteFilesInspect {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    var importer = new DuctImportService();
    var output = new StringBuilder();
    for (String arg : args) {
      output.append("\n=== FILE: ").append(arg).append(" ===\n");
      var sheets = importer.read(Files.readAllBytes(Path.of(arg)), arg);
      for (var sheet : sheets) {
        output.append("-- SHEET: ").append(sheet.get("name")).append('\n');
        output.append("auto_start_row=").append(sheet.get("auto_start_row")).append('\n');
        output.append("auto_mapping=").append(sheet.get("auto_mapping")).append('\n');
        var rows = (List<Map<String, Object>>) sheet.get("rows");
        int shown = 0;
        for (var row : rows) {
          if (shown++ >= 24) break;
          var cells = (List<Object>) row.get("cells");
          var text = cells.toString();
          if (text.length() > 800) text = text.substring(0, 800) + "...";
          output.append("row ").append(row.get("number")).append(": ").append(text).append('\n');
        }
      }
    }
    Files.writeString(Path.of("D:/pig/work/artifact_internal_quote_template/customer-structure.txt"), output.toString(), StandardCharsets.UTF_8);
  }
}
