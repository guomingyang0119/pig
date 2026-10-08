// 安装迁移阶段的 Java/H2 浏览器验收，覆盖指标发布、混合安装、现场费用和导出锁定。
// 需要全新本机隔离预览库，会创建临时指标及报价；表单和旧现场费用断言保留该阶段基准。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = 'http://127.0.0.1:18763', out = path.join(__dirname, 'test-output');
// 直接调用隔离报价接口并核对 HTTP 状态，读取 data 包装；写请求沿用测试资源 revision。
async function api(p, method = 'GET', body) {
  const r = await fetch(base + '/admin/quotation' + p, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const j = await r.json(); assert.equal(r.status, 200, JSON.stringify(j)); return j.data;
}
// 浏览器流程依序执行，页面错误集中收集；无论断言成功或失败都在 finally 关闭浏览器。
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1080 }, acceptDownloads: true });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const button = (name, scope = page) => scope.getByRole('button', { name, exact: true });
  const field = (label, scope = page) => scope.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label').getByText(label, { exact: true }) });
  const fill = (label, value, scope) => field(label, scope).getByRole('textbox').fill(value);
  const choose = async (label, text, scope) => { await field(label, scope).locator('.el-select').click(); await page.getByRole('option', { name: text, exact: true }).click(); };
  // 保存试算后按原报价编号重读结果，保证金额断言来自服务器。
  async function calc(id) { const r = page.waitForResponse(r => r.url().endsWith('/calculate')); await button('保存并试算').click(); await r; await page.waitForFunction(() => !document.querySelector('.el-loading-mask')); return api('/quotes/' + id); }
  // 创建并发布临时指标，发布请求携带最新 revision 以符合资源并发校验。
  async function publish(kind, seed) {
    const { id } = await api('/resources', 'POST', { kind, body: seed });
    const r = (await api('/resources')).find(c => c.id === id);
    await api('/resources/' + id + '/publish', 'POST', { revision: r.revision });
  }
  try {
    await page.goto(base); await page.getByRole('link', { name: '指标管理', exact: true }).click();
    await page.getByRole('tab', { name: '安装指标', exact: true }).click();
    const panel = page.locator('.el-tab-pane:visible');
    await button('从已核对源表建立草稿', panel).click();
    await fill('指标名称', '安装 UI 验收指标', panel);
    assert.equal((await api('/resources')).filter(c => c.kind === 'installation_catalog').length, 0);
    // 编辑四地区固定价和陕西功率分档价，核对中文表单保存的数据结构。
    await panel.getByLabel('共板法兰风管安装单价', { exact: true }).fill('30.5');
    await panel.getByText('陕西', { exact: true }).first().click();
    await panel.getByLabel('消防送/排风、排烟风机低于 1.5kW', { exact: true }).fill('301');
    await button('保存草稿', panel).click(); await page.getByText('安装指标草稿已保存', { exact: true }).waitFor();
    let ic = (await api('/resources')).find(c => c.kind === 'installation_catalog');
    assert.equal(ic.body.projects.length, 52); assert.equal(ic.body.projects[0].price, '30.5');
    assert.equal(ic.body.projects.find(p => p.id === 'sx_19').power_prices[0], '301');
    // 发布后的安装指标应只读，调整价格必须再创建新版本。
    await button('校验指标', panel).click(); await button('发布指标', panel).click(); await button('确定').click();
    await button('创建新版本', panel).waitFor();
    assert.equal(await panel.getByLabel('消防送/排风、排烟风机低于 1.5kW', { exact: true }).isDisabled(), true);
    await page.screenshot({ path: path.join(out, '安装指标-中文价格.png'), fullPage: true });
    // 建立临时风管及外购设备基价，构造同时包含材料与设备的混合订单。
    const dc = await api('/resources/seed', 'POST', {}); const dr = (await api('/resources')).find(c => c.id === dc.id);
    await api('/resources/' + dc.id + '/publish', 'POST', { revision: dr.revision });
    const equipment = await api('/equipment/catalog-seed'); equipment.purchased = [{ id: 'test', name: '测试设备', model: 'TEST', unit: '台', cost: '75' }];
    await publish('equipment_catalog', equipment);
    const created = await api('/quotes', 'POST', { pricing_mode: 'mixed_v1', customer: '安装模块验收客户', project: '安装及整单结算验收' });
    let q = await api('/quotes/' + created.id);
    Object.assign(q.body.duct_options, { area_basis: 'net', product_state: 'semi', shape: 'straight', flange: 'angle', tax_included: false, payment_option: 'no_install_cash' });
    Object.assign(q.body.equipment_options, { tax_included: false, payment_option: 'no_install_cash' });
    // 风管数量按总面积，测试设备按台数，安装单价在后续表单逐行人工确认。
    q.body.rows = [
      { name: '镀锌角钢法兰风管', spec: '0.75 / 800×320', category: '风管', engineering_quantity: '2.5', unit: '㎡', attributes: { thickness: '0.75' } },
      { name: '测试设备', category: '外购设备', quantity: '2', unit: '台', attributes: { model: 'TEST' } },
    ];
    await api('/quotes/' + q.id, 'PUT', { ...q.body, revision: q.revision });
    await page.goto(base + '/quotation/workbench/index?id=' + q.id);
    await page.locator('label.el-checkbox').filter({hasText: '本单包含安装'}).click();
    await choose('安装省区', '贵州'); await page.locator('label.el-checkbox').filter({hasText: /^包含辅材$/}).click();
    await fill('施工标高（米）', '5.01');
    const terms = equipment.payment_terms.find(t => t.code === 'with_install_progress_80');
    await choose('整单付款方式 · 必选', terms.label + ' × ' + terms.coefficient);
    // 人工安装单价分别确认，独立防火包裹项目只增加安装费用，不新增材料行。
    for (const [i, price] of [[0, '7.21'], [1, '7']]) {
      await button('安装条件').nth(i).click(); const dialog = page.getByRole('dialog', { name: '安装项目条件' });
      await fill('人工确认安装单价（未税元 / 单位）', price, dialog); await button('确认条件', dialog).click();
    }
    await button('＋ 添加独立安装项目').click(); let dialog = page.getByRole('dialog', { name: '安装项目条件' });
    await fill('安装名称（独立项目）', '防火包裹', dialog);
    await choose('安装指标项目', '防火板安装 · 38元/㎡', dialog); await fill('安装数量', '2', dialog); await fill('安装单位', '㎡', dialog);
    await button('确认条件', dialog).click();
    // 此处保留当时现场条件需确认及明确零费用的历史验收断言。
    q = await calc(q.id); assert.equal(q.result.total, null); assert.ok(q.result.issues.some(i => i.includes('现场附加费')));
    assert.equal(await button('生成正式报价').isDisabled(), true);
    await fill('现场附加费（未税总额 / 元）', '0'); q = await calc(q.id);
    assert.equal(q.result.formal_ready, true, JSON.stringify(q.result.issues));
    // 核对行安装人工、风管辅材及独立项目汇总，最终整单付款系数只乘一次。
    const fee = q.result.fees.installation;
    assert.equal(Number(fee.untaxed_amount), 128.025); assert.equal(Number(fee.amount), 130);
    assert.equal(Number(q.result.total), Math.ceil((Number(q.result.subtotal) + 130) * 1.1));
    assert.equal(q.body.duct_options.payment_option, 'with_install_progress_80');
    assert.equal(q.body.equipment_options.include_installation, true);
    fs.writeFileSync(path.join(out, 'installation-ui-result.json'), JSON.stringify(q, null, 2));
    await page.screenshot({ path: path.join(out, '安装报价-工作台.png'), fullPage: true });
    // 弹窗取消需保留已经确认的单价，不把未应用修改当作草稿变更。
    await button('安装条件').nth(0).click(); dialog = page.getByRole('dialog', { name: '安装项目条件' });
    await fill('人工确认安装单价（未税元 / 单位）', '999', dialog); await button('取消', dialog).click();
    assert.equal(await button('生成正式报价').isEnabled(), true);
    // 分别导出草稿和定稿文件；定稿后的整单安装开关与行条件均只允许查看。
    const draft = page.waitForEvent('download'); await button('导出 Excel 草稿').click(); await (await draft).saveAs(path.join(out, '安装-浏览器草稿.xlsx'));
    const formal = page.waitForEvent('download'); await button('生成正式报价').click(); await button('确定').click(); await (await formal).saveAs(path.join(out, '安装-浏览器正式.xlsx'));
    await page.getByText('已定稿 · V1', { exact: true }).waitFor();
    assert.equal(await page.getByRole('checkbox', { name: '本单包含安装', exact: true }).isDisabled(), true);
    await button('查看条件').first().click(); dialog = page.getByRole('dialog', { name: '安装项目条件' });
    assert.equal(await field('人工确认安装单价（未税元 / 单位）', dialog).getByRole('textbox').isDisabled(), true);
    await button('关闭', dialog).click();
    assert.deepEqual(errors, []); console.log('PASS: installation catalog, Chinese forms, mixed calculation, pending site fee, explicit zero, cancel, draft/formal export, locked history');
  } catch (e) { await page.screenshot({ path: path.join(out, 'installation-ui-failure.png'), fullPage: true }); throw e; }
  finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
