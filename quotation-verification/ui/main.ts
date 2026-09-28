import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import App from './App.vue';
import Workbench from '../../pig-ui/src/views/quotation/workbench/index.vue';
import Records from '../../pig-ui/src/views/quotation/records/index.vue';
import Catalog from '../../pig-ui/src/views/quotation/catalog/index.vue';
const router = createRouter({ history: createWebHistory(), routes: [
	{ path: '/', redirect: '/quotation/workbench/index' },
	{ path: '/quotation/workbench/index', component: Workbench },
	{ path: '/quotation/records/index', component: Records },
	{ path: '/quotation/catalog/index', component: Catalog },
] });
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app');
