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

/**
 * 报价系统HTTP入口：指标维护、客户清单导入、草稿试算、人工回导及文件交付。
 * 方法注解检查菜单权限，服务层继续核对报价归属、修订号和状态，保证同一业务入口遵守一致规则。
 */
@RestController
@RequestMapping("/quotation")
public class QuoteController {

	private final QuoteService service;
	private final AiQuoteImportService aiImporter;
	private final AiImportJobService aiJobs;

	public QuoteController(QuoteService service, AiQuoteImportService aiImporter, AiImportJobService aiJobs) {
		this.service = service;
		this.aiImporter = aiImporter;
		this.aiJobs = aiJobs;
	}

	/**
	 * 从当前登录会话取得操作者及报价负责人权限，供归属校验和审计使用。
	 * 显示姓名为空时使用登录账号；未登录时直接返回401。
	 */
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

	/**
	 * 供报价页面读取指标列表，具体是否生效由试算时的规则选择确定。
	 */
	@GetMapping("/resources")
	@HasPermission("quote_view")
	public R<?> resources() {
		actor();
		return R.ok(service.resources());
	}

	/**
	 * 由负责人初始化风管指标草稿；已有指标时返回现有记录。
	 */
	@PostMapping("/resources/seed")
	@HasPermission("quote_manage")
	public R<?> seed() {
		return R.ok(service.seed(actor()));
	}

	/**
	 * 返回设备指标初始内容供负责人核对，读取本身不发布指标。
	 */
	@GetMapping("/equipment/catalog-seed")
	@HasPermission("quote_manage")
	public R<?> equipmentSeed() {
		return R.ok(service.equipmentSeed(actor()));
	}

	/**
	 * 返回安装项目及价格初始内容，供负责人创建和审核指标草稿。
	 */
	@GetMapping("/installation/catalog-seed")
	@HasPermission("quote_manage")
	public R<?> installationSeed() {
		return R.ok(service.installationSeed(actor()));
	}

	/**
	 * 按资源类型创建指标草稿；未传类型时兼容风管指标入口。
	 */
	@PostMapping("/resources")
	@HasPermission("quote_manage")
	public R<?> createResource(@RequestBody Map<String, Object> data) {
		return R
			.ok(service.createCatalog(actor(), str(data.getOrDefault("kind", "duct_catalog")), map(data.get("body"))));
	}

	/**
	 * 保存指标草稿正文，并由服务层核对客户端修订号。
	 */
	@PutMapping("/resources/{id}")
	@HasPermission("quote_manage")
	public R<?> editResource(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.editCatalog(actor(), id, data));
	}

	/**
	 * 执行指标校验、发布、修订或停用等动作，动作合法性由当前状态决定。
	 */
	@PostMapping("/resources/{id}/{action}")
	@HasPermission("quote_manage")
	public R<?> resourceAction(@PathVariable String id, @PathVariable String action,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.catalogAction(actor(), id, action, data));
	}

	/**
	 * 读取模板确认状态、金额精度策略及当前引擎版本。
	 */
	@GetMapping("/settings")
	@HasPermission("quote_view")
	public R<?> settings() {
		return R.ok(service.settings(actor()));
	}

	/**
	 * 读取当前操作者可见的产品别名及组合规则。
	 */
	@GetMapping("/product-rules")
	@HasPermission("quote_view")
	public R<?> productRules() {
		return R.ok(service.productRules(actor()));
	}

	/**
	 * 根据产品行返回可用规则建议、模板与系数，不直接改变报价。
	 */
	@PostMapping("/product-rules/recommendations")
	@HasPermission("quote_view")
	public R<?> productRuleRecommendations(@RequestBody Map<String, Object> data) {
		return R.ok(service.productRuleRecommendations(actor(), data));
	}

	/**
	 * 试算尚未保存的产品规则，使用实际报价计算流程展示单行结果。
	 */
	@PostMapping("/product-rules/trial")
	@HasPermission("quote_edit")
	public R<?> trialProductRule(@RequestBody Map<String, Object> data) {
		return R.ok(service.trialProductRule(actor(), data));
	}

	/**
	 * 创建产品规则草稿，普通报价员可维护自己的规则，发布权限另行校验。
	 */
	@PostMapping("/product-rules")
	@HasPermission("quote_edit")
	public R<?> createProductRule(@RequestBody Map<String, Object> data) {
		return R.ok(service.createProductRule(actor(), map(data.get("body"))));
	}

	/**
	 * 更新产品规则草稿；已发布规则需通过新版本维护。
	 */
	@PutMapping("/product-rules/{id}")
	@HasPermission("quote_edit")
	public R<?> editProductRule(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.editProductRule(actor(), id, data));
	}

	/**
	 * 处理产品规则生命周期动作，并核对创建者或负责人权限。
	 */
	@PostMapping("/product-rules/{id}/{action}")
	@HasPermission("quote_edit")
	public R<?> productRuleAction(@PathVariable String id, @PathVariable String action,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.productRuleAction(actor(), id, action, data));
	}

	/**
	 * 由负责人更新正式模板确认状态，影响后续正式导出。
	 */
	@PutMapping("/settings")
	@HasPermission("quote_manage")
	public R<?> settings(@RequestBody Map<String, Object> data) {
		return R.ok(service.changeSettings(actor(), data));
	}

	/**
	 * 检查Excel上传大小后保存解析快照，后续新建报价再确认工作表与列映射。
	 */
	@PostMapping("/imports")
	@HasPermission("quote_edit")
	public R<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的Excel文件");
		return R.ok(service.upload(actor(), file.getBytes(), file.getOriginalFilename()));
	}

	/**
	 * 整理多个客户资料文件为标准清单并创建待核对草稿。
	 * AI整理完成后仍需人工确认，金额计算使用报价规则。
	 */
	@PostMapping("/ai-imports")
	@HasPermission("quote_edit")
	public R<?> aiImport(@RequestParam(value = "files", required = false) MultipartFile[] files,
			@RequestParam(defaultValue = "legacy") String action, @RequestParam(required = false) String jobId) {
		QuoteService.Actor owner = actor();
		if (action.equals("start")) return R.ok(aiJobs.start(owner, files, jobId));
		if (action.equals("next")) return R.ok(aiJobs.next(owner, jobId));
		if (action.equals("cancel")) return R.ok(aiJobs.cancel(owner, jobId));
		if (!action.equals("legacy")) throw new RuleException("未知的整理操作");
		List<Map<String, Object>> rows = aiImporter.normalize(files);
		return R.ok(service.createAiDraft(owner, rows, AiQuoteImportService.sourceSnapshots(files)));
	}

	/** 保持原AI上传路径，查询进度与最近未完成任务，无需修改网关或部署配置。 */
	@GetMapping("/ai-imports")
	@HasPermission("quote_edit")
	public R<?> aiImportStatus(@RequestParam(required = false) String jobId) {
		return R.ok(blank(jobId) ? aiJobs.recent(actor()) : aiJobs.status(actor(), jobId));
	}

	/**
	 * 下载当前AI草稿对应的待核验Excel，带报价及修订信息供人工修改后回导。
	 */
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

	/**
	 * 下载AI导入时保存的原始资料，便于报价人员对照来源核验清单。
	 */
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

	/**
	 * 按归档选项读取报价摘要，普通报价员仅能看到自己的记录。
	 */
	@GetMapping("/quotes")
	@HasPermission("quote_view")
	public R<?> list(@RequestParam(defaultValue = "false") boolean archived) {
		return R.ok(service.listQuotes(actor(), archived));
	}

	/** 分页读取报价摘要；客户/项目搜索与账号可见范围由服务端统一处理。 */
	@GetMapping("/quotes/page")
	@HasPermission("quote_view")
	public R<?> listPage(@RequestParam(defaultValue = "false") boolean archived,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
			@RequestParam(defaultValue = "") String keyword) {
		return R.ok(service.listQuotePage(actor(), archived, page, size, keyword));
	}

	/**
	 * 读取报价正文、试算快照和导出记录，过期草稿结果由服务层标记。
	 */
	@GetMapping("/quotes/{id}")
	@HasPermission("quote_view")
	public R<?> detail(@PathVariable String id) {
		return R.ok(service.detail(actor(), id));
	}

	/**
	 * 根据手工输入或已解析清单建立报价草稿，并保留原始行来源。
	 */
	@PostMapping("/quotes")
	@HasPermission("quote_edit")
	public R<?> create(@RequestBody Map<String, Object> data) {
		return R.ok(service.create(actor(), data));
	}

	/**
	 * 保存当前草稿及供货安装条件；成功后原试算结果失效，需重新计算。
	 */
	@PutMapping("/quotes/{id}")
	@HasPermission("quote_edit")
	public R<?> save(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.save(actor(), id, data));
	}

	/**
	 * 在当前修订号上试算并保存结果，返回待确认项与可否正式报价的状态。
	 */
	@PostMapping("/quotes/{id}/calculate")
	@HasPermission("quote_edit")
	public R<?> calculate(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.calculate(actor(), id, data));
	}

	/**
	 * 上传人工补价的xlsx校对版并生成差异预览，此步骤不改写报价结果。
	 */
	@PostMapping("/quotes/{id}/review-imports")
	@HasPermission("quote_edit")
	public R<?> previewReviewImport(@PathVariable String id, @RequestParam("revision") int revision,
			@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的xlsx校对版");
		return R.ok(service.previewReviewImport(actor(), id, file.getBytes(), file.getOriginalFilename(), revision));
	}

	/**
	 * 预览人工核验后的AI标准清单，核对来源行和字段差异。
	 */
	@PostMapping("/quotes/{id}/ai-input-imports")
	@HasPermission("quote_edit")
	public R<?> previewAiInputImport(@PathVariable String id, @RequestParam("revision") int revision,
			@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty() || file.getSize() > DuctImportService.MAX_BYTES)
			throw new RuleException("请选择24MB以内的完整AI待核验报价单");
		return R.ok(service.previewAiInputImport(actor(), id, file.getBytes(), file.getOriginalFilename(), revision));
	}

	/**
	 * 确认AI清单差异后写回原草稿，清除旧试算并重新进入人工核验状态。
	 */
	@PostMapping("/quotes/{id}/ai-input-imports/{importId}/apply")
	@HasPermission("quote_edit")
	public R<?> applyAiInputImport(@PathVariable String id, @PathVariable String importId,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.applyAiInputImport(actor(), id, importId, data));
	}

	/**
	 * 应用已预览的人工补价结果，并按系统规则重新汇总整单金额。
	 */
	@PostMapping("/quotes/{id}/review-imports/{importId}/apply")
	@HasPermission("quote_edit")
	public R<?> applyReviewImport(@PathVariable String id, @PathVariable String importId,
			@RequestBody Map<String, Object> data) {
		return R.ok(service.applyReviewImport(actor(), id, importId, data));
	}

	/**
	 * 生成校对版或正式Excel，正式导出满足全部条件后锁定报价。
	 */
	@PostMapping("/quotes/{id}/export")
	@HasPermission("quote_export")
	public R<?> export(@PathVariable String id, @RequestBody Map<String, Object> data) {
		return R.ok(service.export(actor(), id, data));
	}

	/**
	 * 处理草稿确认、重新识别、修订、归档、恢复及删除等报价状态动作。
	 */
	@PostMapping("/quotes/{id}/{action}")
	@HasPermission("quote_edit")
	public R<?> action(@PathVariable String id, @PathVariable String action, @RequestBody Map<String, Object> data) {
		return R.ok(service.action(actor(), id, action, data));
	}

	/**
	 * 下载已保存的Excel快照；使用UTF-8文件名并关闭缓存以支持中文报价附件。
	 */
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

	/**
	 * 向报价负责人提供最近的报价业务操作摘要。
	 */
	@GetMapping("/audit")
	@HasPermission("quote_manage")
	public R<?> audit() {
		return R.ok(service.audit(actor()));
	}

	/**
	 * 把输入或业务规则错误转换为400响应，前端可直接展示中文原因。
	 */
	@ExceptionHandler(RuleException.class)
	public ResponseEntity<R<Void>> invalid(RuleException e) {
		return ResponseEntity.badRequest().body(R.failed(e.getMessage()));
	}

	/**
	 * 保留服务层的403、404或409等状态，供页面区分权限、记录和并发冲突。
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<R<Void>> status(ResponseStatusException e) {
		return ResponseEntity.status(e.getStatusCode()).body(R.failed(e.getReason()));
	}

}
