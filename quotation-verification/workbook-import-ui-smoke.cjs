// All uploads and quote writes use the isolated Java/H2 verification server.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const base = 'http://127.0.0.1:18763', out = path.join(__dirname, 'test-output');
async function api(p, method = 'GET', body) {
  const response = await fetch(base + '/admin/quotation' + p, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const json = await response.json(); assert.equal(response.status, 200, JSON.stringify(json)); return json.data;
}
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1080 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const button = (name, scope = page) => scope.getByRole('button', { name, exact: true });
  async function importFile(file, expectedSheets, screenshot) {
    await page.goto(base + '/quotation/workbench/index');
    await button('导入客户清单').click();
    const dialog = page.getByRole('dialog', { name: '导入客户清单' });
    await dialog.getByLabel('选择客户清单').setInputFiles(file);
    await dialog.getByText(`文件包含 ${expectedSheets} 个工作表`, { exact: false }).waitFor();
    assert.equal(await dialog.locator('.el-collapse-item').count(), expectedSheets);
    assert.equal(await dialog.locator('.el-form-item__label').getByText('工作表', { exact: true }).count(), 0);
    assert.equal(await dialog.locator('.el-form-item__label').getByText('报价类型', { exact: true }).count(), 0);
    assert.equal(await button('导入整份清单并创建报价', dialog).isEnabled(), true);
    await page.screenshot({ path: path.join(out, screenshot), fullPage: false, animations: 'disabled' });
    const created = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/quotes') && r.request().method() === 'POST');
    await button('导入整份清单并创建报价', dialog).click();
    const response = await created; assert.equal(response.status(), 200);
    const id = (await response.json()).data.id;
    await dialog.waitFor({ state: 'hidden' });
    await page.locator('.el-form-item__label').getByText('整单是否含运费', { exact: true }).waitFor();
    assert.equal(await page.locator('.el-form-item__label').getByText('整单是否含运费', { exact: true }).count(), 1);
    return api('/quotes/' + id);
  }
  try {
    let fixture;
    for (const ext of ['xls', 'xlsx']) {
      fixture = await importFile(path.join(out, '整单-多工作表.' + ext), 3, '整单导入-' + ext + '.png');
      assert.deepEqual(fixture.body.rows.map(r => r.name), ['防火阀', '未知产品', '镀锌风管']);
      assert.deepEqual(fixture.body.rows.map(r => r.source_sheet), ['设备', '设备', '风管、风阀、风口']);
      assert.deepEqual(fixture.body.rows.map(r => r.source_row), [5, 6, 2]);
      assert.deepEqual(fixture.body.rows.map(r => r.category), ['风阀', '', '风管']);
      assert.deepEqual(fixture.body.source_sheets.map(s => s.status), ['imported', 'imported', 'empty']);
      assert.equal(fixture.body.rows[0].quantity, '2');
      assert.equal(fixture.body.rows[2].engineering_quantity, '12');
      await page.getByText('查看整份文件导入情况（3 个工作表）', { exact: true }).click();
      await page.getByText('设备：已导入 2 个产品行', { exact: true }).waitFor();
      await page.getByText('风管、风阀、风口：已导入 1 个产品行', { exact: true }).waitFor();
    }
    // The common order conditions and original pricing engines still produce the same row prices.
    const field = label => page.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label').getByText(label, { exact: true }) });
    const choose = async (label, text) => { await field(label).locator('.el-select').click(); await page.getByRole('option', { name: text, exact: true }).click(); };
    for (const [label, value] of [['报价含税口径 · 必选', '不含税'], ['整单付款方式 · 必选', /^现金 × 1(?:\.0+)?$/], ['面积结算口径 · 必选', '净面积'], ['成品状态 · 必选', '半成品'], ['管件类型', '直管'], ['法兰形式 · 必选', '角钢法兰']]) await choose(label, value);
    const calculated = page.waitForResponse(r => r.url().endsWith('/calculate'));
    await button('保存并试算').click(); await calculated;
    fixture = await api('/quotes/' + fixture.id);
    assert.equal(Number(fixture.result.rows[0].amount), 252);
    assert.equal(Number(fixture.result.rows[2].amount), 510);
    assert.equal(fixture.result.rows[1].status, 'pending'); assert.equal(fixture.result.total, null);
    await button('参数 / 明细').first().click();
    await page.getByText('原始客户清单内容', { exact: true }).click();
    await page.getByText('设备 · 原表第 5 行', { exact: true }).waitFor();
    await button('完成核对').click();
    const result = { passed: true, formats: ['xls', 'xlsx'], productRows: 3, originalPrices: [252, 510], unknownPreserved: true, sample: null };
    if (process.env.WORKBOOK_SAMPLE) {
      const sample = await importFile(process.env.WORKBOOK_SAMPLE, 2, '整单导入-暖通询价单.png');
      assert.deepEqual(sample.body.source_sheets.map(s => [s.name, s.row_count]), [['设备', 49], ['风管、风阀、风口', 182]]);
      assert.equal(sample.body.rows.length, 231);
      assert.equal(new Set(sample.body.rows.map(r => r.row_id)).size, 231);
      assert.equal(sample.body.rows[0].source_sheet, '设备');
      assert.equal(sample.body.rows[49].source_sheet, '风管、风阀、风口');
      await page.getByText('查看整份文件导入情况（2 个工作表）', { exact: true }).click();
      await page.getByText('设备：已导入 49 个产品行', { exact: true }).waitFor();
      await page.getByText('风管、风阀、风口：已导入 182 个产品行', { exact: true }).waitFor();
      await page.getByText('查看整份文件导入情况（2 个工作表）', { exact: true }).scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(out, '整单导入-231行来源.png'), fullPage: false, animations: 'disabled' });
      result.sample = { worksheets: sample.body.source_sheets, rows: sample.body.rows.length };
    }
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'workbook-import-ui-result.json'), JSON.stringify(result, null, 2));
    console.log('PASS: XLS/XLSX whole-workbook import, independent headers, preserved source order, single freight choice, unchanged prices, unknown rows pending' + (result.sample ? '; customer workbook = 49 + 182 = 231 rows' : ''));
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'workbook-import-ui-failure.png'), fullPage: false, animations: 'disabled' }); throw e;
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
