// 复用本机既有授权会话，经真实 pig 入口只读核对设备指标和中文参数页。
// OAuth 刷新可能更新本机登录缓存，截图及结果写入 test-output；不保存或发布报价业务数据。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async()=>{
  const root=path.resolve(__dirname,'..');
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try{
    // 从 work/local 加载现有登录状态和会话缓存，不在验收输出中展示凭据。
    const context=await browser.newContext({storageState:path.join(root,'work/local/browser-auth.json'),viewport:{width:1512,height:1080}});
    const session=JSON.parse(fs.readFileSync(path.join(root,'work/local/browser-session.json'),'utf8').replace(/^\uFEFF/,''));
    await context.addInitScript(data=>{for(const [k,v]of Object.entries(data))if(!sessionStorage.getItem(k))sessionStorage.setItem(k,v);},session);
    const page=await context.newPage();const errors=[], failures=[];page.on('pageerror',e=>errors.push(e.message));
    // 记录失败响应的路径和状态，用于区分登录过期与业务入口问题。
    page.on('response',r=>{if(r.status()>=400) failures.push({path:new URL(r.url()).pathname,status:r.status()});});
    await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index');
    try {await page.getByRole('button',{name:'新建设备报价',exact:true}).waitFor({timeout:12000});}
    catch(e){
      if(!failures.some(f=>f.status===424))throw e;
      // 仅在 424 登录状态提示时沿用原前端 OAuth 刷新流程，并更新本机缓存。
      await page.evaluate(async()=>{const {useUserInfo}=await import('/src/stores/userInfo.ts');await useUserInfo().refreshToken();});
      await context.storageState({path:path.join(root,'work/local/browser-auth.json')});
      fs.writeFileSync(path.join(root,'work/local/browser-session.json'),JSON.stringify(await page.evaluate(()=>Object.fromEntries(Object.keys(sessionStorage).map(k=>[k,sessionStorage.getItem(k)])))));
      await page.reload();await page.getByRole('button',{name:'新建设备报价',exact:true}).waitFor();
    }
    await page.getByRole('button',{name:'新建复合报价',exact:true}).waitFor();
    // 经原前端 API 读取资源与基准数据，核对 743 条工况和 130 条电机，不创建报价。
    const checks=await page.evaluate(async()=>{
      const {quoteApi}=await import('/src/api/quotation/index.ts');
      const resources=await quoteApi.resources();const seed=await quoteApi.equipmentSeed();
      return {fanPoints:seed.fan_points.length,motors:seed.motors.length,equipmentCatalogs:resources.filter(r=>r.kind==='equipment_catalog').length};
    });
    assert.equal(checks.fanPoints,743);assert.equal(checks.motors,130);
    await page.goto('http://127.0.0.1:8888/#/quotation/catalog/index');
    await page.getByRole('tab',{name:'设备指标',exact:true}).click();
    await page.getByRole('button',{name:'从已核对源表建立草稿',exact:true}).waitFor();
    if (!checks.equipmentCatalogs) {
      // 读取源表只打开页面内存草稿表单，本流程不点击保存或发布。
      await page.getByRole('button',{name:'从已核对源表建立草稿',exact:true}).click();
    }
    // 只浏览五组中文参数页，核验没有需要手写 JSON 的文本域。
    await page.getByRole('tab',{name:'设备参数与选型',exact:true}).click();
    for (const name of ['阀门参数','风口参数','风机本体','风机工况','防爆分档']) {
      await page.getByRole('tab',{name,exact:true}).click();
      assert.equal(await page.getByRole('tabpanel',{name,exact:true}).locator('textarea').count(),0);
    }
    await page.getByRole('tab',{name:'风机工况',exact:true}).click();
    await page.screenshot({path:path.join(__dirname,'test-output/设备参数-真实pig入口.png'),fullPage:true});
    await page.screenshot({path:path.join(__dirname,'test-output/设备指标-真实pig入口.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    // 写出只读检查摘要和截图，业务资源与价格不进行保存或发布。
    fs.writeFileSync(path.join(__dirname,'test-output/equipment-live-readonly.json'),JSON.stringify({passed:true,...checks,chineseParameterTabs:true,noJsonEditors:true},null,2));
    console.log('Live pig gateway, existing login session, equipment entry and catalog seed: PASS; no business writes');
  }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exit(1);});
