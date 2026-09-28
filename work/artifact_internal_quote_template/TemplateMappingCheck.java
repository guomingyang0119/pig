import com.pig4cloud.pig.admin.quotation.DuctImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TemplateMappingCheck {
  public static void main(String[] args) throws Exception {
    var sheets = new DuctImportService().read(Files.readAllBytes(Path.of(args[0])), args[0]);
    if (sheets.size() != 1) throw new IllegalStateException("Expected one worksheet");
    var sheet = sheets.get(0);
    var expected = new LinkedHashMap<String, Object>();
    expected.put("name", 1);
    expected.put("category", 2);
    expected.put("spec", 3);
    expected.put("thickness", 4);
    expected.put("flange", 5);
    expected.put("unit", 6);
    expected.put("quantity", 7);
    expected.put("remark", 8);
    var actual = (Map<String, Object>) sheet.get("auto_mapping");
    if (!actual.equals(expected)) throw new IllegalStateException("Mapping differs: " + actual);
    if (!Integer.valueOf(10).equals(sheet.get("auto_start_row")))
      throw new IllegalStateException("Unexpected start row: " + sheet.get("auto_start_row"));
    assertCategory("镀锌钢板共板法兰风管", "500×320", "风管");
    assertCategory("柜式离心风机", "风量21290m³/h 风压383Pa 功率7.5KW", "风机");
    assertCategory("70℃全自动防火阀", "1250×630", "风阀");
    assertCategory("铝合金防雨百叶风口", "1250×800", "百叶风口");
    assertCategory("阻抗复合型消声器", "800×400", "消声设备");
    System.out.println("PASS: 8 business fields mapped; product rows start at row 10; mixed product categories recognized.");
  }

  private static void assertCategory(String name, String spec, String expected) {
    var row = new LinkedHashMap<String, Object>();
    row.put("name", name);
    row.put("spec", spec);
    row.put("remark", "");
    row.put("category", "");
    row.put("unit", "");
    row.put("quantity", "");
    row.put("engineering_quantity", "");
    row.put("attributes", Map.of());
    DuctImportService.autoClassifyRow(row);
    if (!expected.equals(row.get("category")))
      throw new IllegalStateException(name + " classified as " + row.get("category"));
  }
}
