# 报价迁移验收工具（不属于生产运行代码）

生产应用只使用 Java 和 pig-ui。这里的 Python 源码只用于离线核对，不会打包进 Java 应用。

当前安装基线（选型 2026-09-24，结算规则 2026-09-26）：

- `installation-reference` 与 `installation-source-hashes.json`：Python 安装选型及测试参考副本；安装结算部分已按 2026-09-26 确认的逐行计税取整、现场附加费单列规则更新，不修改原项目。
- `generate_installation_golden.py`：重放旧 1,316 组风管和 2,984 组设备输入，生成 `duct-installation-v1-golden.json.gz`、`equipment-installation-v1-golden.json.gz`；生成新增 734 组 `installation-golden.json.gz`。旧结果文件保留用于追溯，当前 Maven 测试读取新结果。
- `installation-ui-smoke.cjs`：隔离 Java/H2 安装指标发布、中文表单、复合安装结算、现场费用确认、草稿／正式 Excel 及锁定验收。需全新预览内存库，不能指向实际业务数据库。
- `installation-live-readonly.cjs`：真实 pig 已授权登录会话下读取安装入口及 52 项草稿，确认引擎版本；不保存或发布业务数据。
- 下方设备和早期风管脚本是各迁移阶段的历史验收工具；旧“安装费手填总额”及供货付款标签不适用于新安装界面。当前安装操作见 [安装模块使用指南](../quotation-docs/安装模块使用指南.md)。

- generate_golden.py：一次性生成 Python 对照结果和干净的指标草稿。Java 回归测试直接读取已经提交的压缩结果，不执行 Python。
- reference：原业务与测试参照。不要把这里的旧 Web 程序当作 Java 系统的启动入口。
- ui：复用新 Vue 页面和接口类型的独立验收外壳；本机 API 使用真实 Java 业务 + H2 内存库。
- ui-smoke.cjs：Playwright 浏览器工作流验收。先按使用指南启动临时 Java 后端及 UI，再运行此脚本。需要本机已安装 Playwright 和 Edge；可通过 PLAYWRIGHT_MODULE 指定 Playwright 包路径，通过 PLAYWRIGHT_CHANNEL 选择浏览器。
- test-output、日志和 Vite 缓存：测试产生，不提交。

设备模块（2026-09-24）：

- equipment-reference：本次迁移时的 Python 设备规则、共享引擎、测试与设备指标快照；equipment-source-hashes.json 记录来源哈希，后续 Python 安装模块不属于本次基线。
- generate_equipment_golden.py：生成 2,984 组设备／复合报价对照，覆盖全部 743 个风机工况、阀体边界、圆形及远控、风口保底、消声器、整数向上取整、运费临界值、待确认和复合清单。需要原 Python 开发环境的依赖；生产及 Maven 测试不执行 Python。
- equipment-ui-smoke.cjs：同一临时 H2 后端上的设备指标发布、中文公式、阀门报价、风机多候选确认、草稿／正式导出和复合报价页面验收。原 ui-smoke.cjs 继续覆盖风管完整工作流。

临时后端 QuotationPreviewTests 位于 Java 的 src/test 下，只有显式传入 -Dquotation.preview=true 才启动；仅监听 127.0.0.1:18762，45 分钟自动结束。UI 监听 127.0.0.1:18763。这个入口不测试 pig 登录、网关和权限过滤器，不能发布到生产。

完整运行步骤见 [使用指南](../quotation-docs/使用指南.md)，业务覆盖与正式环境待验证事项见 [接入与验证记录](../quotation-docs/接入与验证记录.md)。
