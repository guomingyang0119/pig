// 设备迁移阶段的隔离 Java/H2 浏览器验收：发布指标、报价阀门、确认风机候选及验证混合清单。
// 操作会写入临时库并生成截图/Excel，只适用于本机 18763 预览入口；分类型建单标签保留该阶段基准。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.join(__dirname, 'test-output');
const base = 'http://127.0.0.1:18763';
// 直接调用隔离报价接口并核对 HTTP 状态，读取 data 包装；写请求沿用测试资源 revision。
async function api(p, method='GET', body) {
  const r = await fetch(base+'/admin/quotation'+p, {method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  const j = await r.json(); assert.equal(r.status,200,JSON.stringify(j)); return j.data;
}
// 浏览器流程依序执行，页面错误集中收集；无论断言成功或失败都在 finally 关闭浏览器。
(async()=>{
  const browser = await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL||'msedge'});
  const page = await browser.newPage({viewport:{width:1512,height:1080},acceptDownloads:true});
  const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  const button = name=>page.getByRole('button',{name,exact:true});
  const field = (label,scope=page.locator('main.quotation-page'))=>scope.locator('.el-form-item').filter({has:page.locator('.el-form-item__label').getByText(label,{exact:true})});
  const choose = async(label,option,scope)=>{await field(label,scope).locator('.el-select').click();await page.getByRole('option',{name:option,exact:true}).click();};
  // 报价编号从当前路由读取，避免将试算结果附到另一张清单。
  const qid=()=>new URL(page.url()).searchParams.get('id');
  // 等待保存试算接口和加载层结束，再读取后台结果进行金额断言。
  async function calculate(){const response=page.waitForResponse(r=>r.url().endsWith('/calculate'));await button('保存并试算').click();await response;await page.waitForFunction(()=>!document.querySelector('.el-loading-mask'));return api('/quotes/'+qid());}
  // 复用 500×500 防火阀、2 个的输入，验证最基础的设备成本路径。
  async function addValve(){
    // 加入缺少唯一工况的风机，先验证候选待确认阻止正式导出，再明确选择候选。
    await button('＋ 添加项目').click();const drawer=page.getByRole('dialog');
    await field('产品名称',drawer).getByRole('textbox').fill('防火阀');
    await field('规格描述',drawer).getByRole('textbox').fill('500×500');
    await field('设备数量',drawer).getByRole('textbox').fill('2');
    await field('客户单位',drawer).getByRole('textbox').fill('个');
    await choose('产品类别','风阀',drawer); await button('完成核对').click();
  }
  try{
    await page.goto(base);await page.getByRole('link',{name:'指标管理',exact:true}).click();
    await page.getByRole('tab',{name:'设备指标',exact:true}).click();
    // 源表草稿只打开内存表单；点击保存前资源数量保持不变，发布后才参与报价。
    if (!(await api('/resources')).some(c=>c.kind==='equipment_catalog')) {
    await button('从已核对源表建立草稿').click();await button('保存草稿').waitFor();
    assert.equal((await api('/resources')).filter(c=>c.kind==='equipment_catalog').length,0,'seed must not auto-save or publish');
    await button('保存草稿').click();await page.getByText('设备指标草稿已保存',{exact:true}).waitFor();
    await button('校验指标').click();await page.getByText('设备指标结构、成本、选型工况和付款方案校验通过',{exact:true}).waitFor({timeout:5000}).catch(async()=>{
      // 校验文案可能与风管共用，以发布按钮可用性核对校验通过状态。
      assert.equal(await button('发布指标').isEnabled(),true);
    });
    await button('发布指标').click();await button('确定').click();
    }
    await button('创建新版本').waitFor();
    let ec=(await api('/resources')).find(c=>c.kind==='equipment_catalog');assert.equal(ec.status,'published');assert.equal(ec.body.fan_points.length,743);
    // 确认受限英文成本公式能够显示中文对照，截图作为人工核对依据。
    await page.getByRole('tab',{name:'阀体成本公式',exact:true}).click();
    await page.getByText('中文对照',{exact:true}).first().waitFor();
    await page.screenshot({path:path.join(out,'设备指标-中文公式.png'),fullPage:true});
    await page.getByRole('link',{name:'报价工作台',exact:true}).click();await button('新建设备报价').click();
    await field('客户名称').getByRole('textbox').fill('设备模块验收客户');
    await choose('报价含税口径 · 必选','不含税');await choose('供货付款方式 · 必选','现金 × 1.00');
    // 阀门单价应为整数，现金条件下整行两件金额等于单价乘 2。
    await addValve();let q=await calculate();assert.equal(q.result.formal_ready,true,JSON.stringify(q.result.issues));
    const valveAmount=q.result.total;assert.match(q.result.rows[0].unit_price,/^\d+$/);assert.equal(Number(valveAmount),Number(q.result.rows[0].unit_price)*2);
    await page.screenshot({path:path.join(out,'设备报价-工作台.png'),fullPage:true});
    await button('＋ 添加项目').click();const drawer=page.getByRole('dialog');
    await field('产品名称',drawer).getByRole('textbox').fill('风机');
    await field('规格描述',drawer).getByRole('textbox').fill('HTF(A)-5 功率2.2kW');
    await field('需求型号',drawer).getByRole('textbox').fill('HTF(A)-5');
    await field('需求功率（kW，双速用 /）',drawer).getByRole('textbox').fill('2.2');
    await field('设备数量',drawer).getByRole('textbox').fill('1');await field('客户单位',drawer).getByRole('textbox').fill('台');
    await button('完成核对').click();q=await calculate();
    assert.equal(q.result.rows[1].status,'pending');assert.ok(q.result.rows[1].candidates.length>1);assert.equal(await button('生成正式报价').isDisabled(),true);
    await button('参数 / 明细').nth(1).click();await page.getByRole('dialog').locator('.el-radio').first().click();
    const selected=q.result.rows[1].candidates[0].id;
    await page.screenshot({path:path.join(out,'风机-候选确认.png'),fullPage:true});
    await button('完成核对').click();q=await calculate();assert.equal(q.result.formal_ready,true,JSON.stringify(q.result.issues));
    assert.equal(q.body.rows[1].candidate_id,selected);assert.equal(q.result.rows[1].detail.selected_fan.id,selected);
    // 保存草稿与正式 Excel 后查看窄屏版式，不改变已定稿的计算结果。
    const draft=page.waitForEvent('download');await button('导出 Excel 草稿').click();await(await draft).saveAs(path.join(out,'设备-浏览器草稿.xlsx'));
    const formal=page.waitForEvent('download');await button('生成正式报价').click();await button('确定').click();await(await formal).saveAs(path.join(out,'设备-浏览器正式.xlsx'));
    await page.getByText('已定稿 · V1',{exact:true}).waitFor();
    await page.setViewportSize({width:820,height:1000});await page.screenshot({path:path.join(out,'设备报价-窄屏.png'),fullPage:true});await page.setViewportSize({width:1512,height:1080});

    // 在临时库发布风管指标，再验证混合报价能保持统一税费和付款条件。
    let dc=await api('/resources/seed','POST',{});let resource=(await api('/resources')).find(c=>c.id===dc.id);await api('/resources/'+dc.id+'/publish','POST',{revision:resource.revision});
    await page.getByRole('link',{name:'报价工作台',exact:true}).click();await button('新建复合报价').click();
    await choose('报价含税口径 · 必选','不含税');await choose('供货付款方式 · 必选','现金 × 1.00');
    await choose('面积结算口径 · 必选','净面积');await choose('成品状态 · 必选','半成品');await choose('管件类型','直管');await choose('法兰形式 · 必选','角钢法兰');
    await addValve();await button('＋ 添加项目').click();
    await choose('产品类别','风管',drawer);await field('产品名称',drawer).getByRole('textbox').fill('镀锌风管');
    await field('工程量总面积（㎡）',drawer).getByRole('textbox').fill('100');await field('厚度（mm）',drawer).getByRole('textbox').fill('0.75');
    await button('完成核对').click();q=await calculate();assert.equal(q.result.formal_ready,true,JSON.stringify(q.result.issues));
    assert.equal(q.body.duct_options.tax_included,q.body.equipment_options.tax_included);assert.equal(q.body.duct_options.payment_option,q.body.equipment_options.payment_option);
    assert.equal(Number(q.result.total),Number(valveAmount)+4250);await page.screenshot({path:path.join(out,'复合报价-工作台.png'),fullPage:true});
    // 页面无运行异常并且混合金额等于两类产品各自金额之和，最后留存结果。
    assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'equipment-ui-result.json'),JSON.stringify({passed:true,valveAmount,mixedTotal:q.result.total,fanCandidateConfirmed:true,pagesWithoutErrors:true},null,2));
    console.log('Equipment catalog, Chinese formulas, valve pricing, fan candidate selection, draft/formal exports, mixed UI: PASS');
  }catch(e){await page.screenshot({path:path.join(out,'equipment-ui-failure.png'),fullPage:true});throw e;}
  finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
