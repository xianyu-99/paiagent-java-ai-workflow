const PROVIDER_ALIASES: Record<string, string> = {
  openai: 'openai',
  'open ai': 'openai',
  deepseek: 'deepseek',
  'deep seek': 'deepseek',
  qwen: 'qwen',
  '通义千问': 'qwen',
  moonshot: 'moonshot',
  'moonshot ai': 'moonshot',
  'moonshot-ai': 'moonshot',
  kimi: 'moonshot',
  'kimi moonshot': 'moonshot',
  'kimi / moonshot': 'moonshot',
  '月之暗面': 'moonshot',
  kimi_code: 'kimi_code',
  'kimi code': 'kimi_code',
  'kimi-code': 'kimi_code',
  'kimi coding': 'kimi_code',
  'kimi for coding': 'kimi_code',
  'kimi-for-coding': 'kimi_code',
  'kimi编程': 'kimi_code',
  mimo: 'mimo',
  xiaomi: 'mimo',
  'xiaomi mimo': 'mimo',
  'xiaomi-mimo': 'mimo',
  '小米': 'mimo',
  '小米mimo': 'mimo',
  '小米 mimo': 'mimo',
  step: 'step',
  stepfun: 'step',
  '阶跃星辰': 'step',
  zhipu: 'zhipu',
  '智谱': 'zhipu',
  ai_ping: 'ai_ping',
  'ai ping': 'ai_ping',
};

const PROVIDER_LABELS: Record<string, string> = {
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  qwen: '通义千问',
  moonshot: 'Kimi / Moonshot',
  kimi_code: 'Kimi Code',
  mimo: '小米 MiMo',
  step: '阶跃星辰',
  zhipu: '智谱',
  ai_ping: 'AI Ping',
};

export const SUPPORTED_LLM_PROVIDERS = [
  'openai',
  'deepseek',
  'qwen',
  'moonshot',
  'kimi_code',
  'mimo',
  'step',
  'zhipu',
  'ai_ping',
] as const;

const PROVIDER_DEFAULT_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com',
  deepseek: 'https://api.deepseek.com',
  qwen: 'https://dashscope.aliyuncs.com/compatible-mode',
  moonshot: 'https://api.moonshot.cn',
  kimi_code: 'https://api.kimi.com/coding',
  mimo: 'https://token-plan-cn.xiaomimimo.com',
};

const PROVIDER_MODEL_PLACEHOLDERS: Record<string, string> = {
  openai: '例如: gpt-4o-mini',
  deepseek: '例如: deepseek-chat',
  qwen: '例如: qwen-plus',
  moonshot: '例如: kimi-k2.6',
  kimi_code: '例如: kimi-for-coding',
  mimo: '例如: mimo-v2.5-pro',
};

export const normalizeProviderKey = (provider?: string | null) => {
  if (!provider) {
    return '';
  }

  const trimmed = provider.trim();
  if (!trimmed) {
    return '';
  }

  return PROVIDER_ALIASES[trimmed.toLowerCase()] || trimmed;
};

export const getProviderLabel = (provider?: string | null) => {
  const normalized = normalizeProviderKey(provider);
  return PROVIDER_LABELS[normalized] || provider || '';
};

export const isLegacyProviderNodeType = (nodeType?: string | null) => {
  const normalized = normalizeProviderKey(nodeType);
  return SUPPORTED_LLM_PROVIDERS.includes(normalized as typeof SUPPORTED_LLM_PROVIDERS[number]);
};

export const isLlmNodeType = (nodeType?: string | null) => {
  return nodeType === 'llm' || isLegacyProviderNodeType(nodeType);
};

export const getProviderFromNodeType = (nodeType?: string | null) => {
  return isLegacyProviderNodeType(nodeType) ? normalizeProviderKey(nodeType) : '';
};

export const getSupportedProviderOptions = () =>
  SUPPORTED_LLM_PROVIDERS.map((provider) => ({
    value: provider,
    label: getProviderLabel(provider),
  }));

export const getProviderDefaultBaseUrl = (provider?: string | null) => {
  const normalized = normalizeProviderKey(provider);
  return PROVIDER_DEFAULT_BASE_URLS[normalized] || '';
};

export const getProviderModelPlaceholder = (provider?: string | null) => {
  const normalized = normalizeProviderKey(provider);
  return PROVIDER_MODEL_PLACEHOLDERS[normalized] || '例如: deepseek-chat, moonshot-v1-8k, qwen-plus';
};
