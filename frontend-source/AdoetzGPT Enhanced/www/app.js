(function () {
	const form = document.getElementById('server-form');
	const input = document.getElementById('server-url');
	const status = document.getElementById('status');
	const openSaved = document.getElementById('open-saved');
	const clearSaved = document.getElementById('clear-saved');
	const startVoice = document.getElementById('start-voice');
	const stopVoice = document.getElementById('stop-voice');

	const getNative = () => window.Capacitor?.Plugins?.AdoetzNative;

	const setStatus = (message, error) => {
		status.textContent = message || '';
		status.classList.toggle('error', Boolean(error));
	};

	const normalizeUrl = (value) => {
		const trimmed = String(value || '').trim();
		if (!trimmed) return '';
		if (/^https?:\/\//i.test(trimmed)) return trimmed.replace(/\/+$/, '');
		return `https://${trimmed}`.replace(/\/+$/, '');
	};

	const saveServer = async (serverUrl) => {
		localStorage.setItem('adoetzgpt.serverUrl', serverUrl);
		const native = getNative();
		if (native?.setServerUrl) {
			await native.setServerUrl({ url: serverUrl });
		}
	};

	const clearWebViewCache = async () => {
		const native = getNative();
		if (native?.clearWebViewCache) {
			await native.clearWebViewCache();
		}
	};

	const getSavedServer = async () => {
		const native = getNative();
		if (native?.getServerUrl) {
			const result = await native.getServerUrl();
			if (result?.url) return result.url;
		}
		return localStorage.getItem('adoetzgpt.serverUrl') || '';
	};

	const openServer = async (serverUrl) => {
		const url = normalizeUrl(serverUrl);
		if (!url) {
			setStatus('Enter your OpenWebUI server URL first.', true);
			return;
		}
		await saveServer(url);
		await clearWebViewCache();
		setStatus(`Opening ${url}`);
		window.location.href = url;
	};

	form.addEventListener('submit', async (event) => {
		event.preventDefault();
		await openServer(input.value);
	});

	openSaved.addEventListener('click', async () => {
		await openServer(await getSavedServer());
	});

	clearSaved.addEventListener('click', async () => {
		localStorage.removeItem('adoetzgpt.serverUrl');
		const native = getNative();
		if (native?.clearServerUrl) {
			await native.clearServerUrl();
		}
		input.value = '';
		setStatus('Saved server cleared.');
	});

	startVoice.addEventListener('click', async () => {
		const native = getNative();
		if (!native?.startLiveVoice) {
			setStatus('Native voice bridge is not available in this browser.', true);
			return;
		}
		const result = await native.startLiveVoice({ serverUrl: await getSavedServer() });
		setStatus(result?.running ? 'Native live voice service started.' : 'Voice service did not start.', !result?.running);
	});

	stopVoice.addEventListener('click', async () => {
		const native = getNative();
		if (!native?.stopLiveVoice) {
			setStatus('Native voice bridge is not available in this browser.', true);
			return;
		}
		await native.stopLiveVoice();
		setStatus('Native live voice service stopped.');
	});

	getSavedServer().then((serverUrl) => {
		if (serverUrl) {
			input.value = serverUrl;
			setStatus('Saved server loaded.');
		}
	});
})();
