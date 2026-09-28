// Mutations are restricted to the isolated Java/H2 verification server.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const base = 'http://127.0.0.1:18763', out = path.join(__dirname, 'test-output');
async function api(p, method = 'GET', body) {
  const r = await fetch(base + '/admin/quotation' + p, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const j = await r.json(); assert.equal(r.status, 200, JSON.stringify(j)); return j.data;
}
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1080 }, acceptDownloads: true });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const button = (name, scope = page) => scope.getByRole('button', { name, exact: true });
  const field = (label) => page.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label').getByText(label, { exact: true }) });
  const choose = async (label, text) => { await field(label).locator('.el-select').click(); await page.getByRole('option', { name: text, exact: true }).click(); };
  async function calc(id) {
    const response = page.waitForResponse(r => r.url().endsWith('/calculate'));
    await button('保存并试算').click(); await response;
    await page.waitForFunction(() => !document.querySelector('.el-loading-mask'));
    return api('/quotes/' + id);
  }
  async function conditions() {
    await choose('报价含税口径 · 必选', '不含税');
    await choose('整单付款方式 · 必选', /^现金 × 1(?:\.0+)?$/);
    await choose('面积结算口径 · 必选', '净面积');
    await choose('成品状态 · 必选', '半成品');
    await choose('管件类型', '直管');
    await choose('法兰形式 · 必选', '角钢法兰');
  }
  try {
    if (!(await api('/resources')).some(c => c.kind === 'duct_catalog')) {
      const dc = await api('/resources/seed', 'POST', {}), dr = (await api('/resources')).find(c => c.id === dc.id);
      await api('/resources/' + dc.id + '/publish', 'POST', { revision: dr.revision });
    }
    await page.goto(base + '/quotation/workbench/index');
    assert.equal(await button('新建风管报价').count(), 0);
    await button('新建报价').click(); await page.getByText('自动识别清单', { exact: false }).waitFor();
    let id = new URL(page.url()).searchParams.get('id'), q = await api('/quotes/' + id);
    assert.equal(q.body.recognition_mode, 'auto_v1');
    q.body.rows = [{ name: '镀锌风管', spec: '0.75 / 800×320', engineering_quantity: '12', unit: '㎡', attributes: {} }];
    await api('/quotes/' + id, 'PUT', { ...q.body, revision: q.revision });
    await page.reload(); await conditions(); q = await calc(id);
    assert.equal(Number(q.result.total), 510, JSON.stringify(q.result));
    assert.equal(q.result.pending_count, 0); // no equipment catalog exists yet
    assert.equal(await field('整单是否含运费').count(), 1);
    assert.equal(await field('设备运费').count(), 0);
    assert.equal(await page.getByRole('checkbox', { name: '风管包含运费', exact: true }).count(), 0);
    await choose('整单是否含运费', '含运费'); q = await calc(id);
    assert.equal(Number(q.result.total), 525);
    assert.equal(q.body.duct_options.include_freight, true);
    assert.equal(q.body.equipment_options.include_freight, true);
    await choose('整单是否含运费', '不含运费'); q = await calc(id);
    assert.equal(Number(q.result.total), 510);
    const equipment = await api('/equipment/catalog-seed');
    equipment.purchased = [{ id: 'test', name: '换气扇', model: 'TEST', unit: '台', cost: '75' }];
    if (!(await api('/resources')).some(c => c.kind === 'equipment_catalog')) {
      const ec = await api('/resources', 'POST', { kind: 'equipment_catalog', body: equipment });
      const er = (await api('/resources')).find(c => c.id === ec.id);
      await api('/resources/' + ec.id + '/publish', 'POST', { revision: er.revision });
    }
    await page.goto(base + '/quotation/workbench/index'); await button('导入客户清单').click();
    const dialog = page.getByRole('dialog', { name: '导入客户清单' });
    assert.equal(await dialog.getByRole('radiogroup', { name: '报价类型' }).count(), 0);
    await dialog.getByLabel('选择客户清单').setInputFiles(path.join(out, '自动识别-混合客户清单.xlsx'));
    await dialog.getByText('已识别表头 · 第', { exact: false }).waitFor();
    assert.equal(await dialog.locator('.el-form-item__label').getByText('工作表', { exact: true }).count(), 0);
    await page.screenshot({ path: path.join(out, '自动识别-导入列映射.png'), fullPage: true });
    await button('导入整份清单并创建报价', dialog).click();
    await page.getByText('风管 1 项 / 设备 3 项 / 待识别 1 项', { exact: false }).waitFor();
    id = new URL(page.url()).searchParams.get('id'); q = await api('/quotes/' + id);
    const originalIds = q.body.rows.map(r => r.row_id);
    assert.deepEqual(q.body.rows.map(r => r.category), ['风管', '风阀', '风机', '外购设备', '']);
    assert.deepEqual(q.body.rows.slice(1).map(r => r.quantity), ['2', '1', '3', '4']);
    await conditions(); q = await calc(id);
    assert.equal(q.result.rows[0].status, 'priced'); assert.equal(Number(q.result.rows[0].amount), 510);
    assert.equal(q.result.rows[1].status, 'priced'); assert.equal(Number(q.result.rows[1].amount), 252);
    assert.equal(q.result.rows[3].status, 'priced'); assert.equal(Number(q.result.rows[3].amount), 300);
    assert.equal(q.result.rows[4].status, 'pending'); assert.equal(q.result.rows[4].amount, null);
    assert.equal(q.result.formal_ready, false); assert.equal(await button('生成正式报价').isDisabled(), true);
    assert.equal(q.result.total, null);
    await page.screenshot({ path: path.join(out, '自动识别-逐行报价待确认.png'), fullPage: true });
    // Explicitly exclude out-of-scope rows; original source rows remain intact.
    q.body.rows[2].excluded = q.body.rows[4].excluded = true;
    q.body.rows[2].exclude_reason = q.body.rows[4].exclude_reason = '验收明确排除范围外项目';
    await api('/quotes/' + id, 'PUT', { ...q.body, revision: q.revision });
    await page.reload(); q = await calc(id);
    assert.equal(Number(q.result.total), 1062); assert.equal(q.result.pending_count, 0);
    assert.deepEqual(q.body.rows.map(r => r.row_id), originalIds);
    assert.equal(await field('整单是否含运费').count(), 1);
    assert.equal(await field('设备运费').count(), 0);
    await choose('整单是否含运费', '含运费');
    await choose('送货省区', '贵州'); q = await calc(id);
    assert.equal(q.body.include_freight, true);
    assert.equal(q.body.duct_options.include_freight, true);
    assert.equal(q.body.equipment_options.include_freight, true);
    assert.equal(Number(q.result.rows[0].amount), 524.4);
    assert.equal(q.result.rows[1].status, 'pending'); // Original manual freight rule still applies.
    assert.equal(q.result.rows[3].status, 'pending');
    assert.equal(q.result.total, null);
    for (const index of [1, 3]) {
      await button('参数 / 明细').nth(index).click();
      await field('每台 / 每个运费（元，可填 0）').getByRole('textbox').fill(index === 1 ? '7' : '0');
      await button('完成核对').click();
    }
    q = await calc(id);
    assert.deepEqual([0, 1, 3].map(i => Number(q.result.rows[i].amount)), [524.4, 266, 300]);
    assert.equal(Number(q.result.total), 1091);
    await page.locator('.el-message').last().waitFor({ state: 'hidden' });
    await page.getByRole('columnheader', { name: '#', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(out, '整单运费-混合清单.png'), fullPage: true, animations: 'disabled' });
    const downloaded = page.waitForEvent('download'); await button('导出 Excel 草稿').click();
    await (await downloaded).saveAs(path.join(out, '整单含运费-报价草稿.xlsx'));
    await choose('整单是否含运费', '不含运费'); q = await calc(id);
    assert.equal(q.body.duct_options.include_freight, false);
    assert.equal(q.body.equipment_options.include_freight, false);
    assert.equal(Number(q.result.total), 1062);
    assert.equal(q.body.rows[1].attributes.freight_per_unit, '7'); // Keep entered values for later, but do not charge them.
    q.body.include_freight = null;
    q.body.duct_options.include_freight = true;
    q.body.equipment_options.include_freight = false;
    await api('/quotes/' + id, 'PUT', { ...q.body, revision: q.revision });
    await page.reload();
    await page.getByText('原运费设置未统一，请选择整单是否含运费后重新试算。', { exact: true }).waitFor();
    await choose('整单是否含运费', '不含运费'); q = await calc(id);
    assert.equal(Number(q.result.total), 1062);
    fs.writeFileSync(path.join(out, 'auto-import-ui-result.json'), JSON.stringify(q, null, 2));
    // A legacy whole-duct draft can be converted in place, without another upload.
    const legacy = await api('/quotes', 'POST', { pricing_mode: 'duct_v1', project: '旧草稿重新识别' });
    q = await api('/quotes/' + legacy.id);
    q.body.rows = [{ name: '防火阀', spec: '500×500', category: '风管', engineering_quantity: '2', unit: '个', attributes: {} }];
    await api('/quotes/' + q.id, 'PUT', { ...q.body, revision: q.revision });
    await page.goto(base + '/quotation/workbench/index?id=' + q.id);
    await button('重新识别清单').click();
    await page.getByText('风管 0 项 / 设备 1 项 / 待识别 0 项', { exact: false }).waitFor();
    q = await api('/quotes/' + q.id); assert.equal(q.body.rows[0].category, '风阀'); assert.equal(q.body.rows[0].quantity, '2');
    assert.equal(q.body.recognition_mode, 'auto_v1'); assert.deepEqual(errors, []);
    console.log('PASS: unified import, row recognition, one order freight control, original per-row freight rules, explicit zero, on = 1091 / off = 1062, draft download, unknown rows pending, legacy reclassification');
  } catch (e) { await page.screenshot({ path: path.join(out, 'auto-import-ui-failure.png'), fullPage: true }); throw e; }
  finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
