// 隔离验收 UI 的启动入口，复用生产报价工作台、记录和指标页面，数据来自临时 Java 服务。
import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import App from './App.vue';
import Workbench from '../../pig-ui/src/views/quotation/workbench/index.vue';
import Records from '../../pig-ui/src/views/quotation/records/index.vue';
import Catalog from '../../pig-ui/src/views/quotation/catalog/index.vue';
// 只注册报价业务的三个页面，默认进入工作台，便于浏览器脚本直接访问原页面组件。
const router = createRouter({ history: createWebHistory(), routes: [
	{ path: '/', redirect: '/quotation/workbench/index' },
	{ path: '/quotation/workbench/index', component: Workbench },
	{ path: '/quotation/records/index', component: Records },
	{ path: '/quotation/catalog/index', component: Catalog },
] });
// 安装中文 Element Plus 和路由后挂载，不在此入口启动生产登录或系统菜单。
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app');
