// Read-only verification through the running pig gateway and normal OAuth session.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
(async () => {
  const root = path.resolve(__dirname, '..');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const context = await browser.newContext({ storageState: path.join(root, 'work/local/browser-auth.json'), viewport: { width: 1512, height: 1080 } });
    const session = JSON.parse(fs.readFileSync(path.join(root, 'work/local/browser-session.json'), 'utf8').replace(/^\uFEFF/, ''));
    await context.addInitScript(data => { for (const [k, v] of Object.entries(data)) if (!sessionStorage.getItem(k)) sessionStorage.setItem(k, v); }, session);
    const page = await context.newPage(), errors = [], failures = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('response', r => { if (r.status() >= 400) failures.push({ path: new URL(r.url()).pathname, status: r.status() }); });
    await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index');
    try { await page.getByRole('button', { name: '新建报价', exact: true }).waitFor({ timeout: 12000 }); }
    catch (e) {
      if (!failures.some(f => f.status === 424)) throw e;
      await page.evaluate(async () => { const { useUserInfo } = await import('/src/stores/userInfo.ts'); await useUserInfo().refreshToken(); });
      await context.storageState({ path: path.join(root, 'work/local/browser-auth.json') });
      fs.writeFileSync(path.join(root, 'work/local/browser-session.json'), JSON.stringify(await page.evaluate(() => Object.fromEntries(Object.keys(sessionStorage).map(k => [k, sessionStorage.getItem(k)])))));
      await page.reload(); await page.getByRole('button', { name: '新建报价', exact: true }).waitFor();
    }
    const data = await page.evaluate(async () => {
      const { quoteApi } = await import('/src/api/quotation/index.ts');
      const resources = await quoteApi.resources(), seed = await quoteApi.installationSeed(), settings = await quoteApi.settings();
      return { ids: resources.map(r => r.id).sort(), installations: resources.filter(r => r.kind === 'installation_catalog').length, projects: seed.projects.length, tax: seed.tax_factor, engine: settings.engine_version };
    });
    assert.equal(data.projects, 52); assert.equal(data.tax, '1.01'); assert.equal(data.engine, 'duct-v16');
    await page.locator('.q-table .el-loading-mask').waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: '新建复合报价', exact: true }).count(), 0);
    await page.getByRole('button', { name: '导入客户清单', exact: true }).click();
    const importDialog = page.getByRole('dialog', { name: '导入客户清单' });
    await importDialog.getByText('支持 .xlsx / .xls。整份文件中的清单工作表一起导入', { exact: false }).waitFor();
    assert.equal(await importDialog.getByRole('radiogroup', { name: '报价类型' }).count(), 0);
    await page.screenshot({ path: path.join(__dirname, 'test-output/自动识别-真实pig入口.png'), fullPage: false, animations: 'disabled' });
    await importDialog.getByRole('button', { name: '取消', exact: true }).click();
    const existing = await page.evaluate(async () => {
      const { quoteApi } = await import('/src/api/quotation/index.ts');
      const quotes = await quoteApi.list();
      const draft = quotes.find(q => q.state === 'draft');
      return draft ? { id: draft.id, revision: draft.revision } : null;
    });
    if (existing) {
      await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index?id=' + existing.id);
      const label = page.locator('.el-form-item__label').getByText('整单是否含运费', { exact: true });
      await label.waitFor();
      assert.equal(await label.count(), 1);
      assert.equal(await page.locator('.el-form-item__label').getByText('设备运费', { exact: true }).count(), 0);
      assert.equal(await page.getByRole('checkbox', { name: '风管包含运费', exact: true }).count(), 0);
      await page.screenshot({ path: path.join(__dirname, 'test-output/整单运费-真实pig入口.png'), fullPage: false, animations: 'disabled' });
      const revision = await page.evaluate(async id => { const { quoteApi } = await import('/src/api/quotation/index.ts'); return (await quoteApi.detail(id)).revision; }, existing.id);
      assert.equal(revision, existing.revision);
    }
    await page.goto('http://127.0.0.1:8888/#/quotation/catalog/index');
    await page.getByRole('tab', { name: '安装指标', exact: true }).click();
    if (!data.installations) await page.locator('.el-tab-pane:visible').getByRole('button', { name: '从已核对源表建立草稿', exact: true }).click();
    await page.getByText('未税安装销售单价（元）', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(__dirname, 'test-output/安装指标-真实pig入口.png'), fullPage: true });
    const after = await page.evaluate(async () => { const { quoteApi } = await import('/src/api/quotation/index.ts'); return (await quoteApi.resources()).map(r => r.id).sort(); });
    assert.deepEqual(after, data.ids); assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(__dirname, 'test-output/installation-live-readonly.json'), JSON.stringify({ passed: true, projects: data.projects, publishedCatalogs: data.installations, engine: data.engine, noBusinessWrites: true, failures }, null, 2));
    console.log('PASS: running pig gateway, OAuth session, unified auto import, single whole-order freight selector, installation menu/seed; no business writes');
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
