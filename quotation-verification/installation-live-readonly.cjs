// 经运行中的 pig 网关及普通 OAuth 会话只读核对自动导入、唯一整单运费入口和安装指标。
// 可以刷新本机会话缓存并写截图，业务资源编号和已打开草稿 revision 需保持不变。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
(async () => {
  const root = path.resolve(__dirname, '..');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    // 复用现有授权状态并恢复 sessionStorage，凭据只留在本机已忽略目录。
    const context = await browser.newContext({ storageState: path.join(root, 'work/local/browser-auth.json'), viewport: { width: 1512, height: 1080 } });
    const session = JSON.parse(fs.readFileSync(path.join(root, 'work/local/browser-session.json'), 'utf8').replace(/^\uFEFF/, ''));
    await context.addInitScript(data => { for (const [k, v] of Object.entries(data)) if (!sessionStorage.getItem(k)) sessionStorage.setItem(k, v); }, session);
    const page = await context.newPage(), errors = [], failures = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('response', r => { if (r.status() >= 400) failures.push({ path: new URL(r.url()).pathname, status: r.status() }); });
    await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index');
    try { await page.getByRole('button', { name: '新建报价', exact: true }).waitFor({ timeout: 12000 }); }
    catch (e) {
      // 424 表示需要按原前端刷新会话；其他错误直接失败，不视为已通过入口检查。
      if (!failures.some(f => f.status === 424)) throw e;
      await page.evaluate(async () => { const { useUserInfo } = await import('/src/stores/userInfo.ts'); await useUserInfo().refreshToken(); });
      await context.storageState({ path: path.join(root, 'work/local/browser-auth.json') });
      fs.writeFileSync(path.join(root, 'work/local/browser-session.json'), JSON.stringify(await page.evaluate(() => Object.fromEntries(Object.keys(sessionStorage).map(k => [k, sessionStorage.getItem(k)])))));
      await page.reload(); await page.getByRole('button', { name: '新建报价', exact: true }).waitFor();
    }
    // 一次读取业务资源编号、安装种子及引擎版本，作为后续没有业务写入的对照。
    const data = await page.evaluate(async () => {
      const { quoteApi } = await import('/src/api/quotation/index.ts');
      const resources = await quoteApi.resources(), seed = await quoteApi.installationSeed(), settings = await quoteApi.settings();
      return { ids: resources.map(r => r.id).sort(), installations: resources.filter(r => r.kind === 'installation_catalog').length, projects: seed.projects.length, tax: seed.tax_factor, engine: settings.engine_version };
    });
    assert.equal(data.projects, 52); assert.equal(data.tax, '1.01'); assert.equal(data.engine, 'duct-v16');
    await page.locator('.q-table .el-loading-mask').waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: '新建复合报价', exact: true }).count(), 0);
    // 只打开统一导入弹窗确认整份文件方式，随后取消，不上传资料或新建报价。
    await page.getByRole('button', { name: '导入客户清单', exact: true }).click();
    const importDialog = page.getByRole('dialog', { name: '导入客户清单' });
    await importDialog.getByText('支持 .xlsx / .xls。整份文件中的清单工作表一起导入', { exact: false }).waitFor();
    assert.equal(await importDialog.getByRole('radiogroup', { name: '报价类型' }).count(), 0);
    await page.screenshot({ path: path.join(__dirname, 'test-output/自动识别-真实pig入口.png'), fullPage: false, animations: 'disabled' });
    await importDialog.getByRole('button', { name: '取消', exact: true }).click();
    // 如已有草稿，记录其 revision 后仅打开查看整单运费控件，不能保存更改。
    const existing = await page.evaluate(async () => {
      const { quoteApi } = await import('/src/api/quotation/index.ts');
      for (let page = 1; ; page++) {
        const result = await quoteApi.listPage(false, page, 100);
        const draft = result.records.find(q => q.state === 'draft');
        if (draft) return { id: draft.id, revision: draft.revision };
        if (page * 100 >= result.total) return null;
      }
    });
    if (existing) {
      await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index?id=' + existing.id);
      const label = page.locator('.el-form-item__label').getByText('整单是否含运费', { exact: true });
      await label.waitFor();
      assert.equal(await label.count(), 1);
      assert.equal(await page.locator('.el-form-item__label').getByText('设备运费', { exact: true }).count(), 0);
      assert.equal(await page.getByRole('checkbox', { name: '风管包含运费', exact: true }).count(), 0);
      await page.screenshot({ path: path.join(__dirname, 'test-output/整单运费-真实pig入口.png'), fullPage: false, animations: 'disabled' });
      // 重读同一草稿 revision 验证查看没有产生修订。
      const revision = await page.evaluate(async id => { const { quoteApi } = await import('/src/api/quotation/index.ts'); return (await quoteApi.detail(id)).revision; }, existing.id);
      assert.equal(revision, existing.revision);
    }
    await page.goto('http://127.0.0.1:8888/#/quotation/catalog/index');
    await page.getByRole('tab', { name: '安装指标', exact: true }).click();
    // 尚无安装资源时仅打开源表内存表单，不执行保存或发布。
    if (!data.installations) await page.locator('.el-tab-pane:visible').getByRole('button', { name: '从已核对源表建立草稿', exact: true }).click();
    await page.getByText('未税安装销售单价（元）', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(__dirname, 'test-output/安装指标-真实pig入口.png'), fullPage: true });
    // 前后业务资源 ID 必须完全相同，并要求页面无运行异常。
    const after = await page.evaluate(async () => { const { quoteApi } = await import('/src/api/quotation/index.ts'); return (await quoteApi.resources()).map(r => r.id).sort(); });
    assert.deepEqual(after, data.ids); assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(__dirname, 'test-output/installation-live-readonly.json'), JSON.stringify({ passed: true, projects: data.projects, publishedCatalogs: data.installations, engine: data.engine, noBusinessWrites: true, failures }, null, 2));
    console.log('PASS: running pig gateway, OAuth session, unified auto import, single whole-order freight selector, installation menu/seed; no business writes');
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
