// 隔离验收专用请求适配器，供复用的报价页面访问临时后端。
// 生产 pig-ui 仍使用原认证/加密请求客户端；本文件只由验收 Vite 别名替换。
// 保持报价 API 使用的最小请求形状，支持 JSON 业务请求及 Excel blob 下载。
export default async function request(config: { url: string; method?: string; data?: unknown; responseType?: string }) {
	const headers: Record<string,string> = {};
	let body: BodyInit | undefined;
	// 预览后端接收原始文件字节，文件名通过 X-Filename 传递，不使用生产 multipart 上传协议。
	if (config.data instanceof FormData) {
		const file = config.data.get('file') as File;
		body = file; headers['X-Filename'] = encodeURIComponent(file.name);
	} else if (config.data !== undefined) {
		// 普通业务参数编码为 JSON，不改写报价 body 中的数量、金额文字或 revision。
		headers['Content-Type'] = 'application/json'; body = JSON.stringify(config.data);
	}
	const response = await fetch(config.url, { method: config.method || 'GET', headers, body });
	// HTTP 失败直接抛出服务端错误对象，便于真实报价页面显示原业务提示。
	if (!response.ok) throw await response.json();
	// Excel 导出返回 blob，其他接口保留原 JSON 包装供报价 API 读取。
	return config.responseType === 'blob' ? response.blob() : response.json();
}
