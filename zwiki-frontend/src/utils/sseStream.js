export async function readSseResponse(response, options = {}) {
  const { onMessage, onError } = options;

  if (!response?.ok) {
    throw new Error(`HTTP ${response?.status ?? 'unknown'}`);
  }

  const reader = response.body?.getReader?.();
  if (!reader) {
    throw new Error('SSE response body is empty');
  }

  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let accumulated = '';
  let currentEvent = null;
  let streamDone = false;
  let hasErrorEvent = false;
  let errorMessage = '';

  while (!streamDone) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = String(line || '').replace(/\r$/, '');
      const normalized = trimmed.trim();

      if (!normalized) {
        currentEvent = null;
        continue;
      }

      if (normalized.startsWith('event:')) {
        currentEvent = normalized.slice('event:'.length).trim();
        continue;
      }

      if (!normalized.startsWith('data:')) {
        continue;
      }

      const data = normalized.slice('data:'.length).trimStart();
      if (currentEvent === 'done' || data === '[DONE]') {
        streamDone = true;
        break;
      }

      if (currentEvent === 'error') {
        hasErrorEvent = true;
        errorMessage = data || '抱歉，服务出现问题，请稍后重试。';
        onError?.(errorMessage);
        streamDone = true;
        break;
      }

      if (currentEvent === null || currentEvent === 'message') {
        accumulated += data;
        onMessage?.(accumulated, data);
      }
    }
  }

  if (streamDone) {
    try {
      await reader.cancel();
    } catch {
      // ignore
    }
  }

  return {
    content: accumulated,
    hasErrorEvent,
    errorMessage,
  };
}
