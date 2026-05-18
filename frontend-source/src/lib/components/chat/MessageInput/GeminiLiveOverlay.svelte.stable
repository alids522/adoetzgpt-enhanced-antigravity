<script lang="ts">
	import { onMount, getContext, createEventDispatcher, tick } from 'svelte';
	import { GoogleGenAI, type LiveServerMessage } from '@google/genai';
	import { toast } from 'svelte-sonner';
	import Tooltip from '$lib/components/common/Tooltip.svelte';
	import { WEBUI_API_BASE_URL } from '$lib/constants';
	import { config } from '$lib/stores';

	const i18n = getContext('i18n') as any;
	const dispatch = createEventDispatcher();

	export let eventTarget: EventTarget;
	export let chatId;
	export let modelId;
	export let onMessageAdd: (message: {
		type: string;
		content?: string;
		model?: string;
		modelDisplayName?: string;
		sessionId?: string;
		chatId?: string;
	}) => void = () => {};
	export let conversationContext = '';

	let session: any = null;
	let sessionId =
		globalThis.crypto?.randomUUID?.() ?? `gemini-live-${Date.now()}-${Math.random().toString(36).slice(2)}`;
	let audioContext: AudioContext | null = null;
	let audioStream: MediaStream | null = null;
	let processor: ScriptProcessorNode | null = null;
	let audioSource: AudioBufferSourceNode | null = null;
	let wakeLock: any = null;

	// State
	let isPlaying = false;
	let isListening = false;
	let isMuted = false;
	let isInterrupting = false;
	let rmsLevel = 0;
	let audioQueue: { data: string; mimeType: string }[] = [];

	// Transcription and Chat Log state
	interface ChatMessage {
		role: 'user' | 'ai';
		text: string;
	}
	let chatHistory: ChatMessage[] = [];
	let currentUserMessage = '';
	let currentAiResponse = '';
	let transcriptContainer: HTMLElement;
	let aiStreaming = false;
	let hasUserTurnInSession = false;
	let pendingAssistantResponse = '';
	let pendingAssistantFinal = false;

	let selectedModel = localStorage.getItem('gemini_live_model') || 'models/gemini-3.1-flash-live-preview';
	let selectedVoice = localStorage.getItem('gemini_live_voice') || 'Aoede';
	let selectedPersonality = localStorage.getItem('gemini_live_personality') || 'Assistant';
	let showSettingsMenu = false;

	const VOICES = ['Aoede', 'Kore', 'Puck', 'Charon', 'Fenrir', 'Leda', 'Orus', 'Zephyr'];

	const PERSONALITIES: Record<string, string> = {
		'Assistant': 'You are a helpful, clear, and concise AI assistant. Respond directly and accurately without much filler.',
		'Natural Human': 'You are a relaxed, friendly human having a casual chat. Use natural conversational fillers like "hmm", "uh", or "haha" occasionally. Keep it highly conversational, warm, and empathetic.',
		'Argumentative': 'You are a highly argumentative debater. You constantly challenge the user, question their assumptions, and play devil\'s advocate. Be respectful but aggressively intellectual and skeptical.',
		'Conspiracy': 'You are a paranoid conspiracy theorist. You constantly find hidden connections, suspect secret societies, and speak with dramatic urgency about covered-up truths and hidden agendas.',
		'Sarcastic': 'You are highly sarcastic, witty, and cynical. You answer the user accurately but roll your virtual eyes while doing so. Use dry humor and mild, playful condescension.'
	};

	const SEND_SAMPLE_RATE = 16000;
	const RECEIVE_SAMPLE_RATE = 24000;
	const MIN_DECIBELS = -55;

	const getModelDisplayName = () => selectedModel.replace(/^models\//, '');

	const emitLiveEvent = (message: { type: string; content?: string }) => {
		onMessageAdd({
			...message,
			model: selectedModel,
			modelDisplayName: getModelDisplayName(),
			sessionId,
			chatId
		});
	};

	// Auto-scroll the transcript to the bottom whenever text updates
	const scrollToBottom = async () => {
		await tick();
		if (transcriptContainer) {
			transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
		}
	};

	// Add message to the small local transcript preview only. The source of truth is Chat.svelte.
	const addMessage = (message: ChatMessage) => {
		chatHistory = [...chatHistory, message];
	};

	const appendAssistantText = (text: string) => {
		if (!text) return;

		if (currentAiResponse === "🎙️ (Speaking...)") currentAiResponse = '';
		if (!aiStreaming) {
			emitLiveEvent({ type: 'assistant_start' });
			aiStreaming = true;
		}

		currentAiResponse += text;
		emitLiveEvent({ type: 'assistant_delta', content: text });
	};

	const flushPendingAssistantResponse = () => {
		if (!hasUserTurnInSession || !pendingAssistantResponse.trim()) return;

		const pendingText = pendingAssistantResponse;
		const shouldFinalize = pendingAssistantFinal;
		pendingAssistantResponse = '';
		pendingAssistantFinal = false;

		appendAssistantText(pendingText);

		if (shouldFinalize) {
			if (currentAiResponse.trim()) {
				const textToSave =
					currentAiResponse === "🎙️ (Speaking...)" ? '(Audio Response)' : currentAiResponse.trim();
				addMessage({ role: 'ai', text: textToSave });
				currentAiResponse = '';
			}
			emitLiveEvent({ type: 'assistant_final' });
			aiStreaming = false;
		}
	};

	const finalizeCurrentUserTurn = () => {
		if (!currentUserMessage.trim()) return false;

		const finalText = currentUserMessage.trim();
		addMessage({ role: 'user', text: finalText });
		hasUserTurnInSession = true;
		emitLiveEvent({ type: 'user_final', content: finalText });
		currentUserMessage = '';
		flushPendingAssistantResponse();
		return true;
	};

	$: {
		chatHistory;
		currentUserMessage;
		currentAiResponse;
		scrollToBottom();
	}

	const initSession = async (isReconnect = false) => {
		const apiKey = $config?.gemini?.api_key || import.meta.env.VITE_GEMINI_API_KEY || '';
		const apiBaseUrl = $config?.gemini?.api_base_url || import.meta.env.VITE_GEMINI_API_BASE_URL || '';

		if (!apiKey) {
			toast.error('GEMINI_API_KEY not configured');
			return null;
		}

		try {
			const ai = new GoogleGenAI({
				apiKey,
				...(apiBaseUrl && { baseUrl: apiBaseUrl })
			});

			const basePersonality = PERSONALITIES[selectedPersonality];
			const sharedContext = conversationContext?.trim()
				? `\n\n[SHARED CONVERSATION HISTORY]\nThe following transcript is the canonical Open WebUI chat thread shared by all providers and input modes. Continue from it naturally and preserve facts from every model/user turn:\n${conversationContext.slice(-12000)}`
				: '';
			const systemPrompt = `${basePersonality}${sharedContext}`;

			console.log('Connecting to Gemini Live with model:', selectedModel);

			session = await (ai as any).live.connect({
				model: selectedModel,
				config: {
					systemInstruction: { parts: [{ text: systemPrompt }] },
					responseModalities: ['AUDIO'],
					inputAudioTranscription: {},
					outputAudioTranscription: {},
					speechConfig: {
						voiceConfig: {
							prebuiltVoiceConfig: { voiceName: selectedVoice }
						}
					}
				},
				callbacks: {
					onopen: async () => {
						console.debug('Gemini Live: Opened');
						toast.success('Connected to Gemini Live');
					},
					onmessage: (message: LiveServerMessage) => {
						const serverContent = (message as any).serverContent;
						if (!serverContent) return;

						// 1. NATIVE GEMINI TRANSCRIPT: User Voice
						if (serverContent.inputTranscription) {
							const text = serverContent.inputTranscription.text;
							const isFinished = serverContent.inputTranscription.finished;

							if (text) currentUserMessage += text;

							if (isFinished) finalizeCurrentUserTurn();
						}

						// 2. NATIVE GEMINI TRANSCRIPT: AI Voice
						if (serverContent.outputTranscription) {
							finalizeCurrentUserTurn();

							const text = serverContent.outputTranscription.text;
							const isFinished = serverContent.outputTranscription.finished;

							if (text) {
								if (hasUserTurnInSession) {
									appendAssistantText(text);
								} else {
									pendingAssistantResponse += text;
								}
							}

							if (!hasUserTurnInSession && isFinished && pendingAssistantResponse.trim()) {
								pendingAssistantFinal = true;
							} else if (hasUserTurnInSession && isFinished && currentAiResponse.trim()) {
								// Gemini Live transcription can finish before the turn itself is complete.
								// Keep the shared assistant message open until turnComplete so late chunks
								// append to the same bubble instead of creating sibling response versions.
							}
						}

						// 3. AUDIO CHUNKS
						if (hasUserTurnInSession && serverContent.modelTurn?.parts) {
							const hasOutputTranscript = Boolean(serverContent.outputTranscription?.text);
							for (const part of serverContent.modelTurn.parts) {
								if (part.inlineData?.data) {
									if (!currentAiResponse) {
										currentAiResponse = "🎙️ (Speaking...)";
									}
									audioQueue.push({
										data: part.inlineData.data,
										mimeType: part.inlineData.mimeType
									});
									playNextAudioChunk();
								}
								// Just in case it sends raw text here instead of outputTranscription
								if (part.text && !hasOutputTranscript) {
									appendAssistantText(part.text);
								}
							}
						} else if (!hasUserTurnInSession && serverContent.modelTurn?.parts) {
							const hasOutputTranscript = Boolean(serverContent.outputTranscription?.text);
							for (const part of serverContent.modelTurn.parts) {
								if (part.text && !hasOutputTranscript) {
									pendingAssistantResponse += part.text;
								}
							}
						}

						// 4. INTERRUPTIONS
						if (serverContent.interrupted) {
							console.log('Server acknowledged interrupt.');
							isInterrupting = false;
							audioQueue = [];
							if (audioSource) {
								try { audioSource.stop(); } catch (e) {}
							}
							isPlaying = false;

							if (currentAiResponse.trim()) {
								const textToSave = currentAiResponse === "🎙️ (Speaking...)" ? "(Audio Response)" : currentAiResponse.trim();
								addMessage({ role: 'ai', text: textToSave + ' [Interrupted]' });
								currentAiResponse = '';
							}
							emitLiveEvent({ type: 'assistant_interrupted' });
							aiStreaming = false;
						}

						// 5. TURN COMPLETE
						if (serverContent.turnComplete) {
							console.log('Turn complete');
							isInterrupting = false;

							if (!hasUserTurnInSession && pendingAssistantResponse.trim()) {
								pendingAssistantFinal = true;
								return;
							}

							flushPendingAssistantResponse();

							if (currentAiResponse.trim()) {
								const textToSave = currentAiResponse === "🎙️ (Speaking...)" ? "(Audio Response)" : currentAiResponse.trim();
								addMessage({ role: 'ai', text: textToSave });
								currentAiResponse = '';
							}
							emitLiveEvent({ type: 'assistant_final' });
							aiStreaming = false;
						}
					},
					onerror: (e: ErrorEvent) => {
						console.error('Gemini Live Error:', e);
						toast.error(`Gemini Live error: ${e.message || 'Unknown error'}`);
					},
					onclose: (e: CloseEvent) => {
						console.debug('Gemini Live Closed:', e.code, e.reason);
						session = null;
					}
				}
			});

			return session;
		} catch (error) {
			console.error('Failed to initialize Gemini Live:', error);
			toast.error('Failed to connect to Gemini Live');
			return null;
		}
	};

	const playNextAudioChunk = async () => {
		if (isPlaying || isInterrupting || audioQueue.length === 0) return;

		isPlaying = true;
		const chunk = audioQueue.shift();
		if (!chunk) return;

		try {
			const audioBytes = Uint8Array.from(atob(chunk.data), (c) => c.charCodeAt(0));
			let audioBuffer: AudioBuffer;

			if (!audioContext) {
				isPlaying = false;
				return;
			}

			if (chunk.mimeType.includes('pcm')) {
				const pcmData = new Int16Array(audioBytes.buffer);
				audioBuffer = audioContext.createBuffer(1, pcmData.length, RECEIVE_SAMPLE_RATE);
				const channelData = audioBuffer.getChannelData(0);
				for (let i = 0; i < pcmData.length; i++) {
					channelData[i] = pcmData[i] / 32768;
				}
			} else {
				audioBuffer = await audioContext.decodeAudioData(audioBytes.buffer);
			}

			if (!audioContext || isInterrupting) {
				isPlaying = false;
				return;
			}

			audioSource = audioContext.createBufferSource();
			audioSource.buffer = audioBuffer;
			audioSource.connect(audioContext.destination);

			audioSource.onended = () => {
				isPlaying = false;
				audioSource = null;
				playNextAudioChunk();
			};

			audioSource.start();
		} catch (error) {
			console.error('Error playing audio:', error);
			isPlaying = false;
			playNextAudioChunk();
		}
	};

	const triggerLocalInterrupt = () => {
		if (!isPlaying && audioQueue.length === 0) return;
		console.debug('Local interrupt triggered.');

		isInterrupting = true;
		audioQueue = [];

		if (audioSource) {
			try { audioSource.stop(); } catch (e) {}
		}

		isPlaying = false;

		if (currentAiResponse.trim()) {
			const textToSave = currentAiResponse === "🎙️ (Speaking...)" ? "(Audio Response)" : currentAiResponse.trim();
			chatHistory = [...chatHistory, { role: 'ai', text: textToSave + ' [Interrupted]' }];
			currentAiResponse = '';
		}
		emitLiveEvent({ type: 'assistant_interrupted' });
		aiStreaming = false;
	};

	const startAudioInput = async () => {
		try {
			audioStream = await navigator.mediaDevices.getUserMedia({
				audio: {
					echoCancellation: true,
					noiseSuppression: true,
					autoGainControl: true,
					sampleRate: SEND_SAMPLE_RATE
				}
			});

			audioContext = new AudioContext({ sampleRate: SEND_SAMPLE_RATE });
			if (audioContext.state === 'suspended') {
				await audioContext.resume();
			}
			const source = audioContext.createMediaStreamSource(audioStream);

			const analyser = audioContext.createAnalyser();
			analyser.minDecibels = MIN_DECIBELS;
			analyser.maxDecibels = -10;
			analyser.fftSize = 256;
			source.connect(analyser);

			const dataArray = new Uint8Array(analyser.frequencyBinCount);
			const analyzeAudio = () => {
				if (!audioContext) return;

				analyser.getByteFrequencyData(dataArray);
				const sum = dataArray.reduce((a, b) => a + b, 0);
				rmsLevel = Math.sqrt(sum / dataArray.length) / 255;

				if (isPlaying && rmsLevel > 0.15 && !isInterrupting) {
					triggerLocalInterrupt();
				}

				if (isListening && !isMuted) {
					requestAnimationFrame(analyzeAudio);
				}
			};
			analyzeAudio();

			processor = audioContext.createScriptProcessor(4096, 1, 1);
			source.connect(processor);
			processor.connect(audioContext.destination);

			let processorBuffer: Int16Array[] = [];

			processor.onaudioprocess = (e) => {
				if (!isListening || isMuted || !session) return;

				const inputData = e.inputBuffer.getChannelData(0);

				const pcmData = new Int16Array(inputData.length);
				for (let i = 0; i < inputData.length; i++) {
					const s = Math.max(-1, Math.min(1, inputData[i]));
					pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
				}

				processorBuffer.push(pcmData);

				if (processorBuffer.length >= 2) {
					const combined = new Int16Array(processorBuffer.reduce((acc, arr) => acc + arr.length, 0));
					let offset = 0;
					for (const chunk of processorBuffer) {
						combined.set(chunk, offset);
						offset += chunk.length;
					}
					processorBuffer = [];

					const bytes = new Uint8Array(combined.buffer);
					let binary = '';
					for (let i = 0; i < bytes.byteLength; i++) {
						binary += String.fromCharCode(bytes[i]);
					}
					const base64Data = btoa(binary);

					if (session) {
						try {
							(session as any).sendRealtimeInput({
								audio: {
									mimeType: 'audio/pcm;rate=16000',
									data: base64Data
								}
							});
						} catch (e) {
							console.error('Failed to send audio chunk:', e);
						}
					}
				}
			};

			isListening = true;

		} catch (error) {
			console.error('Error starting audio input:', error);
			toast.error('Failed to access microphone');
		}
	};

	const startListening = () => {
		isListening = true;
	};

	const toggleMute = () => {
		isMuted = !isMuted;
	};

	const setWakeLock = async () => {
		try {
			if ('wakeLock' in navigator) {
				wakeLock = await (navigator as any).wakeLock.request('screen');
			}
		} catch (err) {
			console.log('Wake Lock error:', err);
		}
	};

	onMount(() => {
		eventTarget.dispatchEvent(
			new CustomEvent('gemini:live-status', {
				detail: { active: true, chatId, model: selectedModel, sessionId }
			})
		);
		(async () => {
			await setWakeLock();

			const initializedSession = await initSession();
			if (initializedSession) {
				await startAudioInput();
				startListening();
			}
		})();
		return () => cleanup();
	});

	const cleanup = () => {
		if (session) {
			try { session.close(); } catch (e) {}
			session = null;
		}

		if (audioStream) {
			audioStream.getTracks().forEach((track) => track.stop());
			audioStream = null;
		}

		if (processor) {
			try { processor.disconnect(); } catch (e) {}
			processor = null;
		}

		if (audioSource) {
			try { audioSource.stop(); } catch (e) {}
			audioSource = null;
		}

		if (audioContext) {
			try { audioContext.close(); } catch (e) {}
			audioContext = null;
		}

		isListening = false;
		isPlaying = false;
		isInterrupting = false;
		rmsLevel = 0;
		audioQueue = [];
		chatHistory = [];
		currentUserMessage = '';
		currentAiResponse = '';
		aiStreaming = false;
		hasUserTurnInSession = false;
		pendingAssistantResponse = '';
		pendingAssistantFinal = false;

		if (wakeLock) {
			wakeLock.release();
			wakeLock = null;
		}

		eventTarget.dispatchEvent(
			new CustomEvent('gemini:live-status', {
				detail: { active: false, chatId, model: selectedModel, sessionId }
			})
		);
	};

	const handleClose = async () => {
		if (aiStreaming || currentAiResponse.trim()) {
			emitLiveEvent({ type: 'assistant_final' });
		}
		dispatch('close', {
			chatHistory: chatHistory
		});
		cleanup();
	};

	const handleKeydown = (e: KeyboardEvent) => {
		if (e.key === 'm' || e.key === 'M') {
			const target = e.target as HTMLElement;
			if (
				target.tagName !== 'INPUT' &&
				target.tagName !== 'TEXTAREA' &&
				!target.isContentEditable
			) {
				e.preventDefault();
				toggleMute();
			}
		}
	};
</script>

<svelte:window on:keydown={handleKeydown} />

<div class="max-w-lg w-full h-full max-h-[100dvh] flex flex-col justify-between p-3 md:p-6">
	<!-- Streaming Chat History Log -->
	{#if chatHistory.length > 0 || currentUserMessage || currentAiResponse}
		<div bind:this={transcriptContainer} class="bg-white dark:bg-gray-850 rounded-2xl p-4 mb-4 max-h-56 overflow-y-auto flex flex-col gap-3 shadow-sm border border-gray-100 dark:border-gray-800">
			<!-- Historical Messages -->
			{#each chatHistory as msg}
				<div class="text-sm text-gray-800 dark:text-gray-200">
					<span class="font-bold {msg.role === 'user' ? 'text-blue-600 dark:text-blue-400' : 'text-purple-600 dark:text-purple-400'}">
						{msg.role === 'user' ? 'You:' : 'AI:'}
					</span> {msg.text}
				</div>
			{/each}

			<!-- Currently Streaming User Message -->
			{#if currentUserMessage}
				<div class="text-sm text-gray-800 dark:text-gray-200 opacity-90 transition-opacity">
					<span class="font-bold text-blue-600 dark:text-blue-400">You:</span> {currentUserMessage}
					<span class="animate-pulse inline-block ml-1 h-3 w-1.5 bg-blue-500 rounded"></span>
				</div>
			{/if}

			<!-- Currently Streaming AI Response -->
			{#if currentAiResponse}
				<div class="text-sm text-gray-800 dark:text-gray-200 opacity-90 transition-opacity">
					<span class="font-bold text-purple-600 dark:text-purple-400">AI:</span> {currentAiResponse}
					<span class="animate-pulse inline-block ml-1 h-3 w-1.5 bg-purple-500 rounded"></span>
				</div>
			{/if}
		</div>
	{/if}

	<div class="flex justify-center items-center flex-1 h-full w-full max-h-full">
		<button
			type="button"
			class="relative"
			aria-label={isPlaying ? $i18n.t('Interrupt Gemini Live') : $i18n.t('Gemini Live audio')}
			on:click={() => {
				if (isPlaying) triggerLocalInterrupt();
			}}
		>
			<div
				class="transition-all rounded-full bg-cover bg-center bg-no-repeat {rmsLevel * 100 > 4
					? 'size-52'
					: rmsLevel * 100 > 2
						? 'size-48'
						: rmsLevel * 100 > 1
							? 'size-44'
							: 'size-40'}"
				style={`background-image: url('${WEBUI_API_BASE_URL}/models/model/profile/image?id=${modelId}&lang=${$i18n.language}&voice=true');`}
			></div>
		</button>
	</div>

	<div class="flex flex-col items-center gap-4 pb-4 w-full">
		<button type="button" class="z-10" on:click={() => { if (isPlaying) triggerLocalInterrupt(); }}>
			<div class="line-clamp-1 text-sm font-medium">
				{#if isMuted}
					{$i18n.t('Muted')}
				{:else if isPlaying}
					{$i18n.t('Tap to interrupt')}
				{:else if isListening}
					{$i18n.t('Listening...')}
				{:else}
					{$i18n.t('Connecting...')}
				{/if}
			</div>
		</button>

		<div class="flex items-center justify-center gap-4 z-10">
			<Tooltip content={isMuted ? $i18n.t('Unmute') + ' (M)' : $i18n.t('Mute') + ' (M)'}>
				<button
					class="p-3 rounded-full transition-colors duration-200 {isMuted
						? 'bg-red-500 text-white'
						: 'bg-gray-50 dark:bg-gray-900'}"
					type="button"
					aria-label={isMuted ? $i18n.t('Unmute') : $i18n.t('Mute')}
					on:click={toggleMute}
				>
					{#if isMuted}
						<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="size-5">
							<path stroke-linecap="round" stroke-linejoin="round" d="M12 18.75a6 6 0 0 0 6-6v-1.5m-6 7.5a6 6 0 0 1-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 0 1-3-3V4.5a3 3 0 1 1 6 0v8.25a3 3 0 0 1-3 3Z" />
							<line x1="3" y1="3" x2="21" y2="21" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
						</svg>
					{:else}
						<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="size-5">
							<path stroke-linecap="round" stroke-linejoin="round" d="M12 18.75a6 6 0 0 0 6-6v-1.5m-6 7.5a6 6 0 0 1-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 0 1-3-3V4.5a3 3 0 1 1 6 0v8.25a3 3 0 0 1-3 3Z" />
						</svg>
					{/if}
				</button>
			</Tooltip>

			<Tooltip content={$i18n.t('Settings')}>
				<button
					class="p-3 rounded-full {showSettingsMenu
						? 'bg-purple-100 text-purple-600 dark:bg-purple-900/30'
						: 'bg-gray-50 dark:bg-gray-900'}"
					on:click={() => (showSettingsMenu = !showSettingsMenu)}
					type="button"
					aria-label={$i18n.t('Settings')}
				>
					<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="size-5">
						<path stroke-linecap="round" stroke-linejoin="round" d="M10.5 6h9.75M10.5 6a1.5 1.5 0 1 1-3 0m3 0a1.5 1.5 0 1 0-3 0M3.75 6H7.5m3 12h9.75m-9.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-3.75 0H7.5m9-6h3.75m-3.75 0a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m-9.75 0h9.75" />
					</svg>
				</button>
			</Tooltip>

			<button
				class="p-3 rounded-full bg-gray-50 dark:bg-gray-900"
				on:click={handleClose}
				type="button"
				aria-label={$i18n.t('Close')}
			>
				<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="size-5">
					<path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z" />
				</svg>
			</button>
		</div>

		{#if showSettingsMenu}
			<div
				class="w-full max-w-xs bg-white dark:bg-gray-850 rounded-2xl shadow-xl p-4 flex flex-col gap-4 border border-gray-100 dark:border-gray-800 animate-in fade-in slide-in-from-bottom-2 duration-200"
			>
				<div class="flex flex-col gap-1.5">
					<label for="gemini-live-model" class="text-xs font-semibold text-gray-500 uppercase px-1">{$i18n.t('Model')}</label>
					<select
						id="gemini-live-model"
						class="w-full px-3 py-2 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-gray-800 dark:text-gray-200 text-sm"
						bind:value={selectedModel}
						on:change={async () => {
							localStorage.setItem('gemini_live_model', selectedModel);
							cleanup();
							const newSession = await initSession(true);
							if (newSession) {
								await startAudioInput();
								startListening();
							}
						}}
					>
						<option value="models/gemini-3.1-flash-live-preview">Gemini 3.1 Flash Live (Recommended)</option>
						<option value="models/gemini-live-2.5-flash-native-audio">Gemini 2.5 Live Native Audio</option>
						<option value="models/gemini-2.0-flash-exp">Gemini 2.0 Flash Exp</option>
					</select>
				</div>

				<div class="flex flex-col gap-1.5">
					<div class="text-xs font-semibold text-gray-500 uppercase px-1">{$i18n.t('Voice')}</div>
					<div class="grid grid-cols-4 gap-2">
						{#each VOICES as voice}
							<button
								class="px-2 py-1.5 rounded-lg text-xs transition-all {selectedVoice === voice
									? 'bg-purple-600 text-white shadow-md'
									: 'bg-gray-50 dark:bg-gray-900 hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300'}"
								on:click={async () => {
									if (selectedVoice === voice) return;

									selectedVoice = voice;
									localStorage.setItem('gemini_live_voice', voice);

									cleanup();
									const newSession = await initSession(true);
									if (newSession) {
										await startAudioInput();
										startListening();
									}
								}}
							>
								{voice}
							</button>
						{/each}
					</div>
				</div>

				<div class="flex flex-col gap-1.5 pt-3 border-t border-gray-100 dark:border-gray-800">
					<div class="text-xs font-semibold text-gray-500 uppercase px-1">{$i18n.t('Personality')}</div>
					<div class="grid grid-cols-2 gap-2">
						{#each Object.keys(PERSONALITIES) as persona}
							<button
								class="px-2 py-1.5 rounded-lg text-xs transition-all {selectedPersonality === persona
									? 'bg-blue-600 text-white shadow-md'
									: 'bg-gray-50 dark:bg-gray-900 hover:bg-gray-100 dark:hover:bg-gray-800 text-gray-700 dark:text-gray-300'}"
								on:click={() => {
									if (selectedPersonality === persona) return;

									selectedPersonality = persona;
									localStorage.setItem('gemini_live_personality', persona);

									if (session) {
										triggerLocalInterrupt();

										setTimeout(() => {
											try {
												session.sendRealtimeInput({
													text: `[SYSTEM INSTRUCTION OVERRIDE]: Do NOT forget what we've talked about. Retain all previous knowledge, user details, and context of our conversation. Change ONLY your behavior/persona to exactly this: ${PERSONALITIES[persona]}. Acknowledge this change immediately.`
												});
											} catch(e) {
												console.error("Failed to inject personality:", e);
											}
										}, 300);
									}
								}}
							>
								{persona}
							</button>
						{/each}
					</div>
				</div>
			</div>
		{/if}

		<div class="text-xs text-gray-500 dark:text-gray-400">
			{$i18n.t('Powered by Gemini Live API')}
		</div>
	</div>
</div>
