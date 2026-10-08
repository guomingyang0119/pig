// 隔离页面没有生产登录和权限环境，验收替代函数允许展示全部报价按钮。
// 只由独立验收 Vite 别名引用，不进入 pig-ui 生产构建；真实权限在原应用中校验。
export const auth = (_permission: string) => true;
