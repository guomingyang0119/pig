// Verification transport only. Production imports pig's authenticated/encrypted request client.
export default async function request(config: { url: string; method?: string; data?: unknown; responseType?: string }) {
	const headers: Record<string,string> = {};
	let body: BodyInit | undefined;
	if (config.data instanceof FormData) {
		const file = config.data.get('file') as File;
		body = file; headers['X-Filename'] = encodeURIComponent(file.name);
	} else if (config.data !== undefined) {
		headers['Content-Type'] = 'application/json'; body = JSON.stringify(config.data);
	}
	const response = await fetch(config.url, { method: config.method || 'GET', headers, body });
	if (!response.ok) throw await response.json();
	return config.responseType === 'blob' ? response.blob() : response.json();
}
