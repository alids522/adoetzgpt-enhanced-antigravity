import { browser } from '$app/environment';
import { get, writable, type Writable } from 'svelte/store';
import { WEBUI_API_BASE_URL } from '$lib/constants';

const RECORDS_KEY = 'openwebui.tokenUsage.records';
const COUNTERS_KEY = 'openwebui.tokenUsage.customCounters';
const REMOTE_INFO_KEY = 'token_usage';

export type TokenUsageRecord = {
	id: string;
	timestamp: number;
	inputTokens: number;
	outputTokens: number;
	totalTokens: number;
	model: string;
	provider: string;
	endpoint: string;
	chatId?: string | null;
	messageId?: string | null;
	source?: string;
	counterIds?: string[];
};

export type CustomTokenCounter = {
	id: string;
	name: string;
	createdAt: number;
	resetAt?: number | null;
	models?: string[];
	endpoints?: string[];
	providers?: string[];
};

const persistentWritable = <T>(key: string, initialValue: T): Writable<T> => {
	const storedValue = browser ? localStorage.getItem(key) : null;
	const store = writable<T>(storedValue ? JSON.parse(storedValue) : initialValue);

	if (browser) {
		store.subscribe((value) => {
			localStorage.setItem(key, JSON.stringify(value));
		});
	}

	return store;
};

export const tokenUsageRecords = persistentWritable<TokenUsageRecord[]>(RECORDS_KEY, []);
export const tokenUsageCustomCounters = persistentWritable<CustomTokenCounter[]>(COUNTERS_KEY, []);

let syncReady = false;
let syncInProgress = false;
let syncTimer: ReturnType<typeof setTimeout> | null = null;
let syncingFromServer = false;

export const estimateTokens = (value: unknown) => {
	if (value === null || value === undefined) return 0;
	const text = typeof value === 'string' ? value : JSON.stringify(value);
	return Math.max(0, Math.ceil(text.length / 4));
};

export const formatTokenCount = (value: number) => {
	if (!Number.isFinite(value)) return '0';
	if (value >= 1_000_000) return `${Number(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}M`;
	if (value >= 1_000) return `${Number(value / 1_000).toFixed(value >= 10_000 ? 0 : 1)}K`;
	return `${Math.max(0, Math.round(value))}`;
};

export const extractUsageTokenCounts = (usage: any = {}) => {
	const inputTokens =
		usage?.prompt_tokens ??
		usage?.input_tokens ??
		usage?.inputTokens ??
		usage?.promptTokens ??
		usage?.usage?.prompt_tokens ??
		0;
	const outputTokens =
		usage?.completion_tokens ??
		usage?.output_tokens ??
		usage?.outputTokens ??
		usage?.completionTokens ??
		usage?.usage?.completion_tokens ??
		0;
	const totalTokens =
		usage?.total_tokens ??
		usage?.totalTokens ??
		usage?.usage?.total_tokens ??
		Number(inputTokens) + Number(outputTokens);

	return {
		inputTokens: Number(inputTokens) || 0,
		outputTokens: Number(outputTokens) || 0,
		totalTokens: Number(totalTokens) || Number(inputTokens) + Number(outputTokens) || 0
	};
};

export const resolveModelContextLimit = (model: any, fallback = 128000) => {
	const values = [
		model?.context_length,
		model?.contextLength,
		model?.context_window,
		model?.contextWindow,
		model?.max_context_length,
		model?.maxContextLength,
		model?.max_tokens,
		model?.maxTokens,
		model?.num_ctx,
		model?.numCtx,
		model?.info?.context_length,
		model?.info?.contextLength,
		model?.info?.context_window,
		model?.info?.contextWindow,
		model?.info?.max_context_length,
		model?.info?.maxContextLength,
		model?.info?.max_tokens,
		model?.info?.maxTokens,
		model?.info?.num_ctx,
		model?.info?.params?.num_ctx,
		model?.info?.params?.numCtx,
		model?.info?.params?.ctx,
		model?.info?.params?.context_length,
		model?.info?.params?.contextLength,
		model?.info?.meta?.context_length,
		model?.info?.meta?.contextLength,
		model?.info?.meta?.context_window,
		model?.info?.meta?.contextWindow,
		model?.info?.meta?.max_context_length,
		model?.info?.meta?.maxContextLength,
		model?.info?.meta?.max_tokens,
		model?.info?.meta?.maxTokens,
		model?.info?.meta?.num_ctx,
		model?.info?.meta?.model?.context_length,
		model?.info?.meta?.model?.contextWindow
	];

	const detected = values
		.map((value) => Number(value))
		.find((value) => Number.isFinite(value) && value > 0);

	if (detected) return detected;

	const id = String(model?.id ?? model?.name ?? '').toLowerCase();
	if (id.includes('gemini-1.5') || id.includes('gemini-2') || id.includes('gemini-3')) return 1000000;
	if (id.includes('claude-3') || id.includes('claude-sonnet') || id.includes('claude-opus')) {
		return 200000;
	}
	if (id.includes('gpt-5') || id.includes('gpt-4.1') || id.includes('gpt-4o')) return 128000;
	if (id.includes('llama') || id.includes('mistral') || id.includes('qwen')) return 32768;

	return fallback;
};

export const recordTokenUsage = (record: Omit<TokenUsageRecord, 'id' | 'timestamp'> & {
	id?: string;
	timestamp?: number;
}) => {
	void initTokenUsageSync();
	const normalized: TokenUsageRecord = {
		id: record.id ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`,
		timestamp: record.timestamp ?? Date.now(),
		inputTokens: Math.max(0, Math.round(record.inputTokens || 0)),
		outputTokens: Math.max(0, Math.round(record.outputTokens || 0)),
		totalTokens: Math.max(
			0,
			Math.round(record.totalTokens || record.inputTokens + record.outputTokens || 0)
		),
		model: record.model || 'unknown',
		provider: record.provider || 'unknown',
		endpoint: record.endpoint || record.provider || 'unknown',
		chatId: record.chatId ?? null,
		messageId: record.messageId ?? null,
		source: record.source ?? 'chat',
		counterIds: record.counterIds ?? []
	};

	tokenUsageRecords.update((records) => {
		if (records.some((item) => item.id === normalized.id)) return records;
		return [...records, normalized].slice(-10000);
	});
};

export const resetTokenUsageRecords = (predicate?: (record: TokenUsageRecord) => boolean) => {
	tokenUsageRecords.update((records) => (predicate ? records.filter((record) => !predicate(record)) : []));
};

export const createCustomTokenCounter = (name: string) => {
	const now = Date.now();
	const counter: CustomTokenCounter = {
		id: `counter-${now}-${Math.random().toString(36).slice(2)}`,
		name: name.trim() || 'Custom Counter',
		createdAt: now,
		resetAt: now
	};

	tokenUsageCustomCounters.update((counters) => [...counters, counter]);
	return counter;
};

export const renameCustomTokenCounter = (id: string, name: string) => {
	tokenUsageCustomCounters.update((counters) =>
		counters.map((counter) => (counter.id === id ? { ...counter, name: name.trim() || counter.name } : counter))
	);
};

export const resetCustomTokenCounter = (id: string) => {
	const now = Date.now();
	tokenUsageCustomCounters.update((counters) =>
		counters.map((counter) => (counter.id === id ? { ...counter, resetAt: now } : counter))
	);
};

export const deleteCustomTokenCounter = (id: string) => {
	tokenUsageCustomCounters.update((counters) => counters.filter((counter) => counter.id !== id));
};

export const getTokenUsageSnapshot = () => ({
	records: get(tokenUsageRecords),
	customCounters: get(tokenUsageCustomCounters)
});

const mergeRecords = (localRecords: TokenUsageRecord[], remoteRecords: TokenUsageRecord[]) => {
	const records = new Map<string, TokenUsageRecord>();
	for (const record of [...remoteRecords, ...localRecords]) {
		if (record?.id) records.set(record.id, record);
	}
	return [...records.values()].sort((a, b) => a.timestamp - b.timestamp).slice(-10000);
};

const mergeCounters = (localCounters: CustomTokenCounter[], remoteCounters: CustomTokenCounter[]) => {
	const counters = new Map<string, CustomTokenCounter>();
	for (const counter of [...localCounters, ...remoteCounters]) {
		if (counter?.id) counters.set(counter.id, counter);
	}
	return [...counters.values()].sort((a, b) => a.createdAt - b.createdAt);
};

const syncTokenUsageToServer = async () => {
	if (!browser || !syncReady || syncInProgress || syncingFromServer || !localStorage.token) return;

	syncInProgress = true;
	try {
		await fetch(`${WEBUI_API_BASE_URL}/users/user/info/update`, {
			method: 'POST',
			headers: {
				Accept: 'application/json',
				'Content-Type': 'application/json',
				Authorization: `Bearer ${localStorage.token}`
			},
			body: JSON.stringify({
				[REMOTE_INFO_KEY]: {
					records: get(tokenUsageRecords),
					customCounters: get(tokenUsageCustomCounters),
					updatedAt: Date.now()
				}
			})
		});
	} catch (error) {
		console.error('Failed to sync token usage analytics:', error);
	} finally {
		syncInProgress = false;
	}
};

const scheduleTokenUsageSync = () => {
	if (!browser || syncingFromServer) return;
	if (syncTimer) clearTimeout(syncTimer);
	syncTimer = setTimeout(() => {
		syncTimer = null;
		void syncTokenUsageToServer();
	}, 750);
};

export const initTokenUsageSync = async (token?: string) => {
	if (!browser || syncReady) return;
	const authToken = token ?? localStorage.token;
	if (!authToken) return;

	try {
		const userInfo = await fetch(`${WEBUI_API_BASE_URL}/users/user/info`, {
			headers: {
				Accept: 'application/json',
				Authorization: `Bearer ${authToken}`
			}
		}).then(async (response) => {
			if (!response.ok) throw await response.json();
			return response.json();
		});

		const remote = userInfo?.[REMOTE_INFO_KEY] ?? {};
		syncingFromServer = true;
		tokenUsageRecords.set(mergeRecords(get(tokenUsageRecords), remote.records ?? []));
		tokenUsageCustomCounters.set(
			mergeCounters(get(tokenUsageCustomCounters), remote.customCounters ?? [])
		);
		syncingFromServer = false;
		syncReady = true;
		scheduleTokenUsageSync();
	} catch (error) {
		syncingFromServer = false;
		syncReady = true;
		console.error('Failed to initialize token usage sync:', error);
	}
};

if (browser) {
	tokenUsageRecords.subscribe(() => scheduleTokenUsageSync());
	tokenUsageCustomCounters.subscribe(() => scheduleTokenUsageSync());
}
