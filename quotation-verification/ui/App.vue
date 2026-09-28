<template>
	<div class="preview-shell">
		<aside><h2>科庆 · 报价管理</h2><p>Java 迁移验收</p><nav>
			<router-link to="/quotation/workbench/index">报价工作台</router-link>
			<router-link to="/quotation/records/index">报价记录</router-link>
			<router-link to="/quotation/catalog/index">指标管理</router-link>
		</nav><small>隔离测试入口<br/>真实 Java 计算 · 临时内存数据</small></aside>
		<div class="preview-main"><div class="preview-bar">隔离验收环境 · 不连接现有业务库 · 重启清空测试数据</div><router-view /></div>
	</div>
</template>
<style>
body{margin:0;font-family:Inter,"Microsoft YaHei",sans-serif;background:#f5f6f8;color:#1e293b}
*{box-sizing:border-box}.preview-shell{display:flex;min-height:100vh}aside{width:214px;flex-shrink:0;background:#142238;color:#fff;padding:28px 22px;position:fixed;inset:0 auto 0 0}
aside h2{font-size:18px;margin:0}aside p{color:#92a2b8;font-size:12px;margin-bottom:40px}nav{display:grid;gap:8px}nav a{color:#afbdd0;padding:13px 14px;text-decoration:none;font-size:14px;border-radius:4px}nav a.router-link-active{background:#264e87;color:white}
aside small{position:absolute;bottom:28px;line-height:1.9;color:#92a2b8;font-size:11px}.preview-main{margin-left:214px;width:calc(100% - 214px);padding:0 24px 24px}.preview-bar{height:52px;display:flex;align-items:center;justify-content:flex-end;font-size:12px;color:#738195}
@media(max-width:750px){aside{width:150px;padding:24px 10px}aside h2{font-size:15px}.preview-main{margin-left:150px;width:calc(100% - 150px);padding:0 8px 8px}.preview-bar{font-size:10px}.preview-main .quotation-page{padding:16px}}
</style>
