// Tests use only the isolated Java/H2 preview on 18762/18763, never the business database.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = 'http://127.0.0.1:18763';
const out = path.join(__dirname, 'test-output');
async function api(p, method = 'GET', body) {
  const response = await fetch(base + '/admin/quotation' + p, {
    method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json();
  assert.equal(response.status, 200, JSON.stringify(data));
  return data.data;
}

(async () => {
  const seed = await api('/equipment/catalog-seed');
  const expected = structuredClone(seed);
  // Preserve optional, future and source fields, including numeric strings and nulls.
  expected.verification_metadata = { label: '原样保留', value: null };
  expected.fan_points[0].verification_metadata = { raw: '0.3700', entries: [null, '原始依据'] };
  expected.fan_points[0].airflow_low = '1000.00';
  expected.fan_points[0].pressure_low = '100.00';
  expected.fan_bodies[0].verification_metadata = { raw: '01.00', entries: [null] };
  // Reruns may reuse only this isolated draft.
  let catalog = (await api('/resources')).find(c => c.kind === 'equipment_catalog' && c.status === 'draft');
  let id;
  if (catalog) {
    id = catalog.id;
    await api('/resources/' + id, 'PUT', { revision: catalog.revision, body: expected });
  } else {
    id = (await api('/resources', 'POST', { kind: 'equipment_catalog', body: expected })).id;
  }
  const read = async () => (await api('/resources')).find(c => c.id === id);
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1512, height: 1080 } });
  page.setDefaultTimeout(12000);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const button = (name, scope = page) => scope.getByRole('button', { name, exact: true });
  const tab = name => page.getByRole('tab', { name, exact: true });
  const panel = name => page.getByRole('tabpanel', { name, exact: true });
  const dialog = () => page.getByRole('dialog', { name: /风机/ });
  const input = label => dialog().locator('.el-form-item').filter({
    has: page.locator('.el-form-item__label').getByText(label, { exact: true }),
  }).getByRole('textbox');
  async function chooseFamily(option) {
    await dialog().locator('.el-form-item').filter({has:page.locator('.el-form-item__label').getByText('风机系列', {exact:true})}).locator('.el-select').click();
    await page.getByRole('option', {name:option, exact:true}).click();
  }
  async function apply() {
    await button('应用到草稿', dialog()).click();
    await dialog().waitFor({ state: 'hidden' });
  }
  async function saveAndCompare() {
    const response = page.waitForResponse(r => r.url().endsWith('/resources/' + id) && r.request().method() === 'PUT');
    await button('保存草稿').click();
    assert.equal((await response).status(), 200);
    await page.getByText('草稿可编辑', { exact: true }).waitFor();
    assert.deepEqual((await read()).body, expected);
  }
  async function load() {
    await page.goto(base + '/quotation/catalog/index');
    await tab('设备指标').click();
    await button(seed.name).click();
    await tab('设备参数与选型').click();
    await tab('阀门参数').waitFor();
  }
  try {
    await load();
    for (const name of ['阀门参数', '风口参数', '风机本体', '风机工况', '防爆分档']) {
      await tab(name).click();
      assert.equal(await panel(name).locator('textarea').count(), 0, 'no JSON editors');
    }
    await tab('风机工况').click();
    await page.getByText('找到 743 条 / 全部 743 条', { exact: true }).waitFor();
    assert.equal(await button('编辑', panel('风机工况')).count(), 15);
    await button('编辑', panel('风机工况')).first().click();
    assert.equal(await input('功率（kW，双速用 / 分隔）').inputValue(), expected.fan_points[0].powers.join(' / '));
    await apply();
    await tab('风机本体').click();
    await button('编辑', panel('风机本体')).first().click();
    assert.equal(await input('板厚（mm，可留空）').inputValue(), '');
    await page.getByText('中文对照：', { exact: false }).first().waitFor();
    await apply();
    await saveAndCompare();
    console.log('No-op view/apply/save preserves every field, array, null and source: PASS');

    await tab('阀门参数').click();
    await page.getByRole('textbox', { name: '防火阀 · 基础机构成本（元）', exact: true }).fill('31.2500');
    expected.valves.fire.mechanism = '31.2500';
    await tab('风口参数').click();
    await page.getByRole('textbox', { name: '单层百叶 · 面积成本（元/㎡）', exact: true }).fill('81.2300');
    expected.louvers.single[0] = '81.2300';
    await tab('防爆分档').click();
    await page.getByRole('textbox', { name: '第1档成本上限（元）', exact: true }).fill('1100.00');
    await page.getByRole('textbox', { name: '第1档防爆成本倍率', exact: true }).fill('1.4500');
    expected.explosion_tiers[0] = { limit: '1100.00', factor: '1.4500' };
    await saveAndCompare();
    console.log('Chinese valve/louver/tier inputs retain exact decimal strings: PASS');

    await tab('风机工况').click();
    await button('编辑', panel('风机工况')).first().click();
    await input('风量（m³/h）').fill('1888.00');
    await input('功率（kW，双速用 / 分隔）').fill('0.55 / 0.3700');
    await input('电机极数（多档用 / 分隔）').fill('4 / 6');
    await input('核对说明 / 隔离原因').fill('已核对；保留其他来源字段');
    Object.assign(expected.fan_points[0], { airflow: '1888.00', powers: ['0.55', '0.3700'], poles: ['4', '6'], quality_note: '已核对；保留其他来源字段' });
    await apply();
    await saveAndCompare();
    await button('编辑', panel('风机工况')).first().click();
    await input('风量（m³/h）').fill('99999');
    await page.getByText('有未保存修改', { exact: true }).waitFor();
    await button('取消', dialog()).click();
    await page.getByText('本次参数修改尚未应用到草稿，确认放弃？', { exact: true }).waitFor();
    await button('确定', page.getByRole('dialog', { name: '请确认' })).click();
    await page.getByText('草稿可编辑', { exact: true }).waitFor();
    await saveAndCompare();
    const quarantined = expected.fan_points.filter(p => p.quarantined);
    await panel('风机工况').locator('.el-checkbox').click();
    await page.getByText(`找到 ${quarantined.length} 条 / 全部 743 条`, { exact: true }).waitFor();
    await button('编辑', panel('风机工况')).first().click();
    assert.equal(await input('核对说明 / 隔离原因').inputValue(), quarantined[0].quality_note);
    await apply();
    await panel('风机工况').locator('.el-checkbox').click();
    await panel('风机工况').locator('.el-select').click();
    await page.getByRole('option', {name:'SWF · 混流风机', exact:true}).click();
    const swfCount = expected.fan_points.filter(p => p.family === 'SWF').length;
    await page.getByText(`找到 ${swfCount} 条 / 全部 743 条`, { exact: true }).waitFor();
    await page.getByRole('textbox', { name:'搜索风机工况', exact:true }).fill('不存在的工况');
    await page.getByText('找到 0 条 / 全部 743 条', { exact: true }).waitFor();
    await saveAndCompare();
    console.log('Point editing, ordered dual-speed values, cancel, quarantine and filtering: PASS');

    await tab('风机本体').click();
    await button('编辑', panel('风机本体')).first().click();
    await input('板厚（mm，可留空）').fill('1.20');
    await input('金属材料成本公式（元）').fill('82*(steel_price)/1000');
    await page.getByText('中文对照：82×(风机材料吨价)÷1000', { exact: true }).waitFor();
    await apply();
    expected.fan_bodies[0].thickness = '1.20';
    expected.fan_bodies[0].material_expression = '82*(steel_price)/1000';
    await saveAndCompare();
    await button('编辑', panel('风机本体')).first().click();
    await input('板厚（mm，可留空）').fill('');
    await apply();
    expected.fan_bodies[0].thickness = null;
    await saveAndCompare();

    await button('新增本体参数').click();
    await chooseFamily('HTFC · 柜式离心风机');
    await input('机号').fill('99');
    await input('材料吨价（元/吨）').fill('4000');
    await input('金属材料成本公式（元）').fill('size * steel_price / 1000');
    await input('其他成本公式（元）').fill('100');
    await input('依据来源').fill('隔离验收新增本体');
    await apply();
    await page.getByRole('textbox', {name:'搜索风机本体', exact:true}).fill('隔离验收新增本体');
    assert.equal(await button('移除', panel('风机本体')).count(), 1);
    await button('移除', panel('风机本体')).click();
    await button('确定').click();
    await saveAndCompare();
    await page.getByRole('textbox', {name:'搜索风机本体', exact:true}).fill('');

    await tab('风机工况').click();
    await button('新增工况').click();
    await chooseFamily('SWF · 混流风机');
    for (const [name,value] of [['风机型号','SWF-99 验收新增'],['机号','99'],['转速（r/min）','1000'],['风量（m³/h）','1234'],['风压（Pa）','200'],['功率（kW，双速用 / 分隔）','3 / 2.2'],['电机极数（多档用 / 分隔）','4 / 6'],['依据来源','隔离验收新增工况']]) await input(name).fill(value);
    await apply();
    await page.getByRole('textbox', {name:'搜索风机工况', exact:true}).fill('验收新增');
    await page.getByText('找到 1 条 / 全部 744 条', {exact:true}).waitFor();
    await button('移除', panel('风机工况')).click();
    await button('确定').click();
    await saveAndCompare();
    await tab('防爆分档').click();
    await button('新增成本档位').click();
    await page.getByRole('textbox', {name:'第3档成本上限（元）', exact:true}).fill('3000');
    await page.getByRole('textbox', {name:'第3档防爆成本倍率', exact:true}).fill('1.8');
    await button('移除', panel('防爆分档')).nth(2).click();
    await button('确定').click();
    await saveAndCompare();
    console.log('Body formula display, null thickness, add/remove points, bodies and tiers: PASS');

    await tab('阀门参数').click();
    await page.getByRole('textbox', { name:'防火阀 · 基础机构成本（元）', exact:true }).fill('999');
    await page.getByRole('link', {name:'报价工作台', exact:true}).click();
    await page.getByText('设备指标有未保存修改，确定放弃？', {exact:true}).waitFor();
    await button('取消').click();
    assert.ok(page.url().endsWith('/quotation/catalog/index'));
    await page.getByRole('textbox', { name:'防火阀 · 基础机构成本（元）', exact:true }).fill(expected.valves.fire.mechanism);
    await page.getByText('草稿可编辑', {exact:true}).waitFor();
    catalog = await read();
    await api('/resources/' + id, 'PUT', {revision:catalog.revision, body:seed});
    await load();
    await button('校验指标').click();
    await page.getByText('指标结构、价格、公式和付款方案校验通过', {exact:true}).waitFor();
    await button('发布指标').click();
    await button('确定').click();
    await button('创建新版本').waitFor();
    assert.deepEqual((await read()).body, seed);
    for (const name of ['阀门参数', '风口参数', '防爆分档']) {
      await tab(name).click();
      for (const box of await panel(name).getByRole('textbox').all()) assert.equal(await box.isDisabled(), true);
    }
    await tab('风机工况').click();
    assert.equal(await button('编辑', panel('风机工况')).count(), 0);
    assert.equal(await button('新增工况').count(), 0);
    await button('查看', panel('风机工况')).first().click();
    assert.equal(await input('风量（m³/h）').isDisabled(), true);
    assert.equal(await button('应用到草稿').count(), 0);
    await page.screenshot({path:path.join(out,'设备参数-工况详情.png'), animations:'disabled'});
    await button('关闭', dialog()).click();
    await dialog().waitFor({state:'hidden'});
    await page.screenshot({path:path.join(out,'设备参数-风机工况.png'), fullPage:true, animations:'disabled'});
    await page.setViewportSize({width:820,height:1000});
    await page.screenshot({path:path.join(out,'设备参数-窄屏.png'), fullPage:true, animations:'disabled'});
    await page.setViewportSize({width:1512,height:1080});
    await tab('风口参数').click();
    await page.screenshot({path:path.join(out,'设备参数-风口参数.png'), fullPage:true, animations:'disabled'});
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out,'equipment-parameters-ui-result.json'),JSON.stringify({passed:true, fanPoints:seed.fan_points.length, fullBodyRoundtrip:true, readonlyPublished:true, canceledChangesDiscarded:true, sourceAndUnknownFieldsPreserved:true, pageErrors:errors},null,2));
    console.log('Unsaved guard, validation, publication and read-only behavior: PASS');
  } catch (e) {
    await page.screenshot({path:path.join(out,'equipment-parameters-failure.png'), fullPage:true});
    throw e;
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1);});
