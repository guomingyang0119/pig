// 隔离报价 UI 的 Vite 配置：复用 pig-ui 依赖和真实报价页面，接口转到临时 Java 后端。
import { defineConfig } from '../../pig-ui/node_modules/vite/dist/node/index.js';
import vue from '../../pig-ui/node_modules/@vitejs/plugin-vue/dist/index.mjs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
// 按当前配置文件位置定位验收壳和主前端，启动工作目录不影响路径解析。
const root = path.dirname(fileURLToPath(import.meta.url));
const ui = path.resolve(root, '../../pig-ui');
export default defineConfig({
	root, plugins: [vue()],
	// 验收请求和权限函数先替换为本地实现，其他 /@ 引用仍从 pig-ui/src 解析。
	resolve: { alias: [
		{ find: '/@/utils/request', replacement: path.join(root, 'request.ts') },
		{ find: '/@/utils/authFunction', replacement: path.join(root, 'auth.ts') },
		{ find: '/@', replacement: path.join(ui, 'src') },
		// 统一复用主前端 Vue、路由及组件库，避免不同实例导致组件上下文不一致。
		...['vue', 'vue-router', 'element-plus'].map(name => ({ find: name, replacement: path.join(ui, 'node_modules', name) })),
	] },
	// 仅监听本机 18763，端口冲突直接报错；报价接口与停止测试入口代理到 18762 临时后端。
	server: { host: '127.0.0.1', port: 18763, strictPort: true, fs: { allow: [path.resolve(root, '../..')] },
		proxy: { '/admin/quotation': 'http://127.0.0.1:18762', '/__stop': 'http://127.0.0.1:18762' } },
});
