// 风管迁移初期的浏览器流程验收，仅连接本机 18763 隔离入口，会创建指标、报价和导出文件。
// 其中分类型建单及整单手填安装金额属于历史界面基准；当前规则应以现行源码和对应测试为准。
// PLAYWRIGHT_MODULE 可指定已有 Playwright 包，PLAYWRIGHT_CHANNEL 可选择浏览器渠道。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
// 截图、草稿、正式报价和结构化结果写入验收输出目录，不作为生产配置。
const out = path.join(__dirname, 'test-output');
fs.mkdirSync(out, { recursive: true });
// 预览 UI 的本机固定地址，经 Vite 代理访问临时 Java 后端。
const base = 'http://127.0.0.1:18763';
// 直接调用隔离报价接口并核对 HTTP 状态，读取 data 包装；写请求沿用测试资源 revision。
async function api(p, method='GET', data) {
	const r = await fetch(base + '/admin/quotation' + p, { method, headers: { 'Content-Type': 'application/json' }, body: data === undefined ? undefined : JSON.stringify(data) });
	const j = await r.json(); assert.equal(r.status, 200, JSON.stringify(j)); return j.data;
}
// 浏览器流程依序执行，页面错误集中收集；无论断言成功或失败都在 finally 关闭浏览器。
(async()=>{
	const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
	const page = await browser.newPage({ viewport: { width: 1512, height: 1080 }, acceptDownloads: true });
	const errors = []; page.on('pageerror', e=>errors.push(e.message));
	// 按完整中文按钮和字段标签定位，避免点击名称相近的其他控件。
	const button = name=>page.getByRole('button', {name,exact:true});
	const field = label=>page.locator('main.quotation-page .el-form-item').filter({ has: page.locator('.el-form-item__label').getByText(label, {exact:true}) });
	const choose = async(label, option)=>{ await field(label).locator('.el-select').click(); await page.getByRole('option',{name:option,exact:true}).click(); };
	try {
		await page.goto(base);
		await page.getByRole('link', {name:'指标管理',exact:true}).click();
		// 先建立并发布风管基准，后续手工报价应只使用明确的正式指标。
		if ((await api('/resources')).length === 0) { await button('初始化基价草稿').click(); await button('校验指标').waitFor(); }
		if ((await api('/resources')).some(c=>c.status==='draft')) {
			await button('校验指标').click(); await page.getByText('指标结构、价格、公式和付款方案校验通过',{exact:true}).waitFor();
			await button('发布指标').click(); await button('确定').click();
		}
		await button('创建新版本').waitFor();
		await page.screenshot({path:path.join(out,'指标管理.png'),fullPage:true});
		await page.getByRole('link',{name:'报价工作台',exact:true}).click();
		await button('新建风管报价').click(); await button('保存并试算').waitFor();
		// 录入净面积、半成品、直管及角钢条件，构造可核对的风管基础报价。
		await field('客户名称').getByRole('textbox').fill('迁移验收客户');
		await field('项目名称').getByRole('textbox').fill('贵阳风管 · Java 验收');
		await choose('面积结算口径 · 必选','净面积');
		await choose('成品状态 · 必选','半成品');
		await choose('管件类型','直管');
		await choose('法兰形式 · 必选','角钢法兰');
		await choose('报价含税口径 · 必选','不含税');
		await button('＋ 添加项目').click();
		const drawer=page.getByRole('dialog');
		await drawer.locator('.el-form-item').filter({hasText:'产品名称'}).getByRole('textbox').fill('镀锌风管');
		await drawer.locator('.el-form-item').filter({hasText:'工程量总面积（㎡）'}).getByRole('textbox').fill('100');
		await drawer.locator('.el-form-item').filter({hasText:'厚度（mm）'}).getByRole('textbox').fill('0.75');
		await drawer.locator('.el-form-item').filter({hasText:'尺寸 / 型号'}).getByRole('textbox').fill('800×320');
		await button('完成核对').click();
		await button('保存并试算').click();
		await page.getByText('试算完成，请核对待确认项',{exact:true}).waitFor();
		// 未选付款方案时只能保留试算待确认，不能静默采用现金系数。
		assert.equal(await button('生成正式报价').isDisabled(),true,'payment cannot silently default');
		await page.getByRole('radio',{name:'现金 系数 × 1.00',exact:true}).click();
		await button('保存并试算').click();
		await page.getByText('试算完成，可以生成正式报价',{exact:true}).waitFor();
		const id = new URL(page.url()).searchParams.get('id');
		let q=await api('/quotes/'+id);
		assert.equal(q.result.total,'4250.0');
		await page.waitForFunction(()=>!document.querySelector('.el-message'));
		await page.screenshot({path:path.join(out,'报价工作台.png'),fullPage:true});
		await button('参数 / 明细').click();
		await page.getByText('上次计算过程',{exact:true}).waitFor();
		await page.screenshot({path:path.join(out,'逐行计算明细.png'),fullPage:true});
		await button('完成核对').click();
		// 分别下载草稿和正式文件，并核验定稿后不能原地编辑，修订版清除旧试算结果。
		const draft=page.waitForEvent('download');await button('导出 Excel 草稿').click();
		await (await draft).saveAs(path.join(out,'浏览器-草稿.xlsx'));
		const formal=page.waitForEvent('download');await button('生成正式报价').click();await button('确定').click();
		await (await formal).saveAs(path.join(out,'浏览器-正式报价.xlsx'));
		await page.getByText('已定稿 · V1',{exact:true}).waitFor();
		assert.equal(await button('保存并试算').count(),0,'formal quote is immutable');
		await button('创建修订版').click();await button('确定').click();
		await page.getByText('草稿 · V2',{exact:true}).waitFor();
		q=await api('/quotes/'+new URL(page.url()).searchParams.get('id'));assert.equal(q.result,null);
		await page.getByRole('link',{name:'报价记录',exact:true}).click();
		assert.equal((await api('/quotes')).length>=2,true);
		await page.screenshot({path:path.join(out,'报价记录.png'),fullPage:true});
		// 核对历史版本导出记录，再通过归档与恢复保持原定稿状态。
		const original = await api('/quotes/'+id);assert.equal(original.state,'final');assert.equal(original.exports.length,2);
		const v1=page.locator('.el-table__body tr').filter({hasText:'V1'}).filter({hasText:'迁移验收客户'}).first();
		const archived=page.waitForResponse(r=>r.url().endsWith('/'+id+'/archive'));
		await v1.getByRole('button',{name:'归档',exact:true}).click();await button('确定').click();await archived;
		await page.getByText('已归档',{exact:true}).click();
		const restored=page.waitForResponse(r=>r.url().endsWith('/'+id+'/restore'));
		await button('恢复').first().click();await button('确定').click();await restored;
		assert.equal((await api('/quotes/'+id)).state,'final');
		await page.getByRole('link',{name:'报价工作台',exact:true}).click();
		await button('导入客户清单').click();
		// 导入预先准备的 xls 样本，核对工程量与规格字段，确保付款方式仍需人工选择。
		await page.getByLabel('选择客户清单').setInputFiles(path.join(out,'客户验收.xls'));
		await page.getByText('原表预览 · 前 12 个有内容的行',{exact:true}).waitFor();
		await page.waitForFunction(()=>!document.querySelector('.el-loading-mask'));
		await page.screenshot({path:path.join(out,'导入列映射.png'),fullPage:true});
		await button('确认映射并创建报价').click();await button('保存并试算').waitFor();
		await page.getByRole('dialog',{name:'导入客户清单',exact:true}).waitFor({state:'hidden'});
		let imported=await api('/quotes/'+new URL(page.url()).searchParams.get('id'));
		assert.equal(imported.body.rows.length,1);assert.equal(imported.body.rows[0].engineering_quantity,'10');
		assert.equal(imported.body.rows[0].spec,'0.75');assert.equal(imported.body.duct_options.payment_option,'');
		await field('客户名称').getByRole('textbox').fill('导入验收客户');
		await field('项目名称').getByRole('textbox').fill('Excel 导入与安装报价');
		await choose('面积结算口径 · 必选','净面积');await choose('成品状态 · 必选','半成品');
		await choose('管件类型','直管');await choose('法兰形式 · 必选','角钢法兰');
		await choose('报价含税口径 · 必选','含税');
		// 下面的整单安装手填总额是早期迁移基准，断言只记录当时的含税与付款规则。
		await page.getByText('本单包含安装',{exact:true}).click();
		await page.getByLabel('未税安装费').fill('200');
		await page.getByRole('radio',{name:'付款进度70% 系数 × 1.20',exact:true}).click();
		await button('保存并试算').click();await page.getByText('试算完成，可以生成正式报价',{exact:true}).waitFor();
		imported=await api('/quotes/'+imported.id);
		assert.equal(imported.result.fees.installation.amount,'202.0');
		assert.equal(imported.result.payment.code,'with_install_progress_70');
		await page.screenshot({path:path.join(out,'含安装报价.png'),fullPage:true});
		const installed=page.waitForEvent('download');await button('生成正式报价').click();await button('确定').click();
		await (await installed).saveAs(path.join(out,'浏览器-带安装.xlsx'));
		assert.deepEqual(errors,[]);
		// 写出完成的流程清单与浏览器异常，便于复核本次验收覆盖范围。
		fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify({passed:true,total:'4250.0',checks:['初始化并发布指标','付款必选阻断','手工建单','保存试算','计算明细','草稿下载','正式报价与锁定','V2修订','记录列表','归档与恢复','xls上传与列映射','含安装付款方案','带安装正式导出'],pageErrors:errors},null,2));
		console.log('PASS: real Java service + Vue UI browser workflow; total 4250.0');
	// 失败时留存页面截图和末尾可见文字，方便定位具体业务步骤。
	} catch(e) {
		await page.screenshot({path:path.join(out,'browser-failure.png'),fullPage:true});
		console.error((await page.locator('body').innerText()).slice(-7000)); throw e;
	} finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
