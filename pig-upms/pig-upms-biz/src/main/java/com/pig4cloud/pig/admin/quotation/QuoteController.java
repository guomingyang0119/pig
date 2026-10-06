package com.pig4cloud.pig.admin.quotation;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.common.security.annotation.HasPermission;
import com.pig4cloud.pig.common.security.service.PigUser;
import com.pig4cloud.pig.common.security.util.SecurityUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.pig4cloud.pig.admin.quotation.QuoteData.*;

@RestController
@RequestMapping("/quotation")
public class QuoteController {

	private final QuoteService service;
	private final AiQuoteImportService aiImporter;

	public QuoteController(QuoteService service, AiQuoteImportService aiImporter) {
		this.service = service;
		this.aiImporter = aiImporter;
	}

	private QuoteService.Actor actor() {
		PigUser user = SecurityUtils.getUser();
		if (user == null)
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
		boolean manager = SecurityUtils.getAuthentication()
			.getAuthorities()
			.stream()
			.anyMatch(a -> a.getAuthority().equals("quote_manage"));
		return new QuoteService.Actor(user.getId(), blank(user.getName()) ? user.getUsername() : user.getName(),
				manager);
	}

	@GetMapping("/resources")
	@HasPermission("quote_view")
	public R<?> resources() {
		actor();
		return R.ok(service.resources());
	}

	@PostMapping("/resources/seed")
	@HasPermission("quote_manage")
	public R<?> seed() {
		return R.ok(service.seed(actor()));
	}

	@GetMapping("/equipment/catalog-seed")
	@HasPermission("quote_manage")
	public R<?> equipmentSeed() {
		return R.ok(service.equipmentSeed(actor()));
	}

	@GetMapping("/installation/catalog-seed")
	@HasPermission("quote_manage")
	public R<?> installationSeed() {
		return R.ok(service.installationSeed(actor()));
	}

	@PostMapping("/resources")
	@HasPermission("quote_manage")
	public R<?> createResource(@RequestBody Map<String, Object> data) {
		return R
			.ok(service.createCatalog(actor(), str(data.getOrDefault("kind", "duct_catalog")), map(data.get("body"))));
	}

	@PutMapping("/resources/{id}")
	@HasPermission("quote_manage")
	public R<?> editResource(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.editCatalog(actor(), id, data));
	}

	@PostMapping("/resources/{id}/{action}")
	@HasPermission("quote_manage")
	public R<?> resourceAction(@PathVariable String id, @PathVariable String action,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.catalogAction(actor(), id, action, data));
	}

	@GetMapping("/settings")
	@HasPermission("quote_view")
	public R<?> settings() {
		return R.ok(service.settings(actor()));
	}

	@GetMapping("/product-rules")
	@HasPermission("quote_view")
	public R<?> productRules() {
		return R.ok(service.productRules(actor()));
	}

	@PostMapping("/product-rules/recommendations")
	@HasPermission("quote_view")
	public R<?> productRuleRecommendations(@RequestBody Map<String, Object> data) {
		return R.ok(service.productRuleRecommendations(actor(), data));
	}

	@PostMapping("/product-rules/trial")
	@HasPermission("quote_edit")
	public R<?> trialProductRule(@RequestBody Map<String, Object> data) {
		return R.ok(service.trialProductRule(actor(), data));
	}

	@PostMapping("/product-rules")
	@HasPermission("quote_edit")
	public R<?> createProductRule(@RequestBody Map<String, Object> data) {
		return R.ok(service.createProductRule(actor(), map(data.get("body"))));
	}

	@PutMapping("/product-rules/{id}")
	@HasPermission("quote_edit")
	public R<?> editProductRule(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.editProductRule(actor(), id, data));
	}

	@PostMapping("/product-rules/{id}/{action}")
	@HasPermission("quote_edit")
	public R<?> productRuleAction(@PathVariable String id, @PathVariable String action,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.productRuleAction(actor(), id, action, data));
	}

	@PutMapping("/settings")
	@HasPermission("quote_manage")
	public R<?> settings(@RequestBody Map<String, Object> data) {
		return R.ok(service.changeSettings(actor(), data));
	}

	@PostMapping("/imports")
	@HasPermission("quote_edit")
	public R<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的Excel文件");
		return R.ok(service.upload(actor(), file.getBytes(), file.getOriginalFilename()));
	}

	@PostMapping("/ai-imports")
	@HasPermission("quote_edit")
	public R<?> aiImport(@RequestParam("files") MultipartFile[] files) {
		QuoteService.Actor owner = actor();
		List<Map<String, Object>> rows = aiImporter.normalize(files);
		return R.ok(service.createAiDraft(owner, rows, AiQuoteImportService.sourceSnapshots(files)));
	}

	@GetMapping("/quotes/{id}/standard-input")
	@HasPermission("quote_view")
	public ResponseEntity<byte[]> standardInput(@PathVariable String id) {
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("AI待核验报价单.xlsx", StandardCharsets.UTF_8).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(service.aiStandardWorkbook(actor(), id));
	}

	@GetMapping("/quotes/{id}/ai-sources/{index}")
	@HasPermission("quote_view")
	public ResponseEntity<byte[]> aiSource(@PathVariable String id, @PathVariable int index) {
		Map<String, Object> source = service.aiSource(actor(), id, index);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(s(source, "media_type")))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(s(source, "filename"), StandardCharsets.UTF_8).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body((byte[]) source.get("content"));
	}

	@GetMapping("/quotes")
	@HasPermission("quote_view")
	public R<?> list(@RequestParam(defaultValue = "false") boolean archived) {
		return R.ok(service.listQuotes(actor(), archived));
	}

	@GetMapping("/quotes/{id}")
	@HasPermission("quote_view")
	public R<?> detail(@PathVariable String id) {
		return R.ok(service.detail(actor(), id));
	}

	@PostMapping("/quotes")
	@HasPermission("quote_edit")
	public R<?> create(@RequestBody Map<String, Object> data) {
		return R.ok(service.create(actor(), data));
	}

	@PutMapping("/quotes/{id}")
	@HasPermission("quote_edit")
	public R<?> save(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.save(actor(), id, data));
	}

	@PostMapping("/quotes/{id}/calculate")
	@HasPermission("quote_edit")
	public R<?> calculate(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.calculate(actor(), id, data));
	}

	@PostMapping("/quotes/{id}/review-imports")
	@HasPermission("quote_edit")
	public R<?> previewReviewImport(@PathVariable String id, @RequestParam("revision") int revision,
			@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的xlsx校对版");
		return R.ok(service.previewReviewImport(actor(), id, file.getBytes(), file.getOriginalFilename(), revision));
	}

	@PostMapping("/quotes/{id}/ai-input-imports")
	@HasPermission("quote_edit")
	public R<?> previewAiInputImport(@PathVariable String id, @RequestParam("revision") int revision,
			@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的完整AI待核验报价单");
		return R.ok(service.previewAiInputImport(actor(), id, file.getBytes(), file.getOriginalFilename(), revision));
	}

	@PostMapping("/quotes/{id}/ai-input-imports/{importId}/apply")
	@HasPermission("quote_edit")
	public R<?> applyAiInputImport(@PathVariable String id, @PathVariable String importId,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.applyAiInputImport(actor(), id, importId, data));
	}

	@PostMapping("/quotes/{id}/review-imports/{importId}/apply")
	@HasPermission("quote_edit")
	public R<?> applyReviewImport(@PathVariable String id, @PathVariable String importId,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.applyReviewImport(actor(), id, importId, data));
	}

	@PostMapping("/quotes/{id}/export")
	@HasPermission("quote_export")
	public R<?> export(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.export(actor(), id, data));
	}

	@PostMapping("/quotes/{id}/{action}")
	@HasPermission("quote_edit")
	public R<?> action(@PathVariable String id, @PathVariable String action, @RequestBody Map<String, Object> data) {
		return R.ok(service.action(actor(), id, action, data));
	}

	@GetMapping("/exports/{id}")
	@HasPermission("quote_export")
	public ResponseEntity<byte[]> download(@PathVariable String id) {
		Map<String, Object> file = service.download(actor(), id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(s(file, "filename"), StandardCharsets.UTF_8)
						.build()
						.toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body((byte[]) file.get("content"));
	}

	@GetMapping("/audit")
	@HasPermission("quote_manage")
	public R<?> audit() {
		return R.ok(service.audit(actor()));
	}

	@ExceptionHandler(RuleException.class)
	public ResponseEntity<R<Void>> invalid(RuleException e) {
		return ResponseEntity.badRequest().body(R.failed(e.getMessage()));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<R<Void>> status(ResponseStatusException e) {
		return ResponseEntity.status(e.getStatusCode()).body(R.failed(e.getReason()));
	}

}
