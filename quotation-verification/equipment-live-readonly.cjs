// Uses an already authorized local browser session; performs read-only checks.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async()=>{
  const root=path.resolve(__dirname,'..');
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try{
    const context=await browser.newContext({storageState:path.join(root,'work/local/browser-auth.json'),viewport:{width:1512,height:1080}});
    const session=JSON.parse(fs.readFileSync(path.join(root,'work/local/browser-session.json'),'utf8').replace(/^\uFEFF/,''));
    await context.addInitScript(data=>{for(const [k,v]of Object.entries(data))if(!sessionStorage.getItem(k))sessionStorage.setItem(k,v);},session);
    const page=await context.newPage();const errors=[], failures=[];page.on('pageerror',e=>errors.push(e.message));
    page.on('response',r=>{if(r.status()>=400) failures.push({path:new URL(r.url()).pathname,status:r.status()});});
    await page.goto('http://127.0.0.1:8888/#/quotation/workbench/index');
    try {await page.getByRole('button',{name:'新建设备报价',exact:true}).waitFor({timeout:12000});}
    catch(e){
      if(!failures.some(f=>f.status===424))throw e;
      // Renew through pig's normal OAuth refresh flow; never bypass its login checks.
      await page.evaluate(async()=>{const {useUserInfo}=await import('/src/stores/userInfo.ts');await useUserInfo().refreshToken();});
      await context.storageState({path:path.join(root,'work/local/browser-auth.json')});
      fs.writeFileSync(path.join(root,'work/local/browser-session.json'),JSON.stringify(await page.evaluate(()=>Object.fromEntries(Object.keys(sessionStorage).map(k=>[k,sessionStorage.getItem(k)])))));
      await page.reload();await page.getByRole('button',{name:'新建设备报价',exact:true}).waitFor();
    }
    await page.getByRole('button',{name:'新建复合报价',exact:true}).waitFor();
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
      // Loading the seed only opens an in-memory form; no save/publication is performed.
      await page.getByRole('button',{name:'从已核对源表建立草稿',exact:true}).click();
    }
    await page.getByRole('tab',{name:'设备参数与选型',exact:true}).click();
    for (const name of ['阀门参数','风口参数','风机本体','风机工况','防爆分档']) {
      await page.getByRole('tab',{name,exact:true}).click();
      assert.equal(await page.getByRole('tabpanel',{name,exact:true}).locator('textarea').count(),0);
    }
    await page.getByRole('tab',{name:'风机工况',exact:true}).click();
    await page.screenshot({path:path.join(__dirname,'test-output/设备参数-真实pig入口.png'),fullPage:true});
    await page.screenshot({path:path.join(__dirname,'test-output/设备指标-真实pig入口.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    fs.writeFileSync(path.join(__dirname,'test-output/equipment-live-readonly.json'),JSON.stringify({passed:true,...checks,chineseParameterTabs:true,noJsonEditors:true},null,2));
    console.log('Live pig gateway, existing login session, equipment entry and catalog seed: PASS; no business writes');
  }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exit(1);});
