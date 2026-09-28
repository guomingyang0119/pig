import { defineConfig } from '../../pig-ui/node_modules/vite/dist/node/index.js';
import vue from '../../pig-ui/node_modules/@vitejs/plugin-vue/dist/index.mjs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
const ui = path.resolve(root, '../../pig-ui');
export default defineConfig({
	root, plugins: [vue()],
	resolve: { alias: [
		{ find: '/@/utils/request', replacement: path.join(root, 'request.ts') },
		{ find: '/@/utils/authFunction', replacement: path.join(root, 'auth.ts') },
		{ find: '/@', replacement: path.join(ui, 'src') },
		...['vue', 'vue-router', 'element-plus'].map(name => ({ find: name, replacement: path.join(ui, 'node_modules', name) })),
	] },
	server: { host: '127.0.0.1', port: 18763, strictPort: true, fs: { allow: [path.resolve(root, '../..')] },
		proxy: { '/admin/quotation': 'http://127.0.0.1:18762', '/__stop': 'http://127.0.0.1:18762' } },
});
