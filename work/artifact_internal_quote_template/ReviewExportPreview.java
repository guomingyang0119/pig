import com.pig4cloud.pig.admin.quotation.DuctExportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.pig4cloud.pig.admin.quotation.QuoteData.obj;

public class ReviewExportPreview {
  public static void main(String[] args) throws Exception {
    var rows = List.of(
        obj("name", "柜式离心风机", "spec", "风量21290m³/h 风压383Pa 功率7.5kW", "unit", "台", "quantity", "2", "engineering_quantity", "", "remark", "", "attributes", obj()),
        obj("name", "待选型排烟风机", "spec", "风量待确认", "unit", "台", "quantity", "1", "engineering_quantity", "", "remark", "客户要求防爆", "attributes", obj())
    );
    var resultRows = List.of(
        obj("status", "priced", "pricing_mode", "equipment_v1", "billing_unit", "台", "billing_quantity", "2", "unit_price", "1000", "amount", "2000", "detail", obj()),
        obj("status", "pending", "pricing_mode", "equipment_v1", "reason", "待确认风量、风压及防爆等级", "detail", obj())
    );
    var body = obj("pricing_mode", "equipment_v1", "customer", "校对版测试客户", "project", "混合通风工程", "contact", "张工 13800000000", "delivery_location", "贵阳", "quote_date", "2026-09-26", "note", "", "include_freight", false,
        "rows", rows, "equipment_options", obj("tax_included", false, "include_freight", false), "duct_options", obj("include_freight", false), "installation_options", obj("included", false));
    var result = obj("rows", resultRows, "subtotal", "2000", "total", null, "issues", List.of("第2项待确认风量、风压及防爆等级"), "fees", obj("installation", obj()), "payment", obj("label", "现金付款", "coefficient", "1"), "formal_ready", false);
    byte[] bytes = new DuctExportService().write(body, result, "PREVIEW-V1", false,
        obj("quote_id", "preview-quote-id", "revision", 3, "family", "preview-family", "version", 1));
    Files.write(Path.of("D:/pig/quotation-verification/test-output/报价校对版-预览.xlsx"), bytes);
  }
}
