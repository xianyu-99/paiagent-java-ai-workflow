const PROVIDER_ALIASES: Record<string, string> = {
  openai: 'openai',
  'open ai': 'openai',
  deepseek: 'deepseek',
  'deep seek': 'deepseek',
  qwen: 'qwen',
  '通义千问': 'qwen',
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
  step: '阶跃星辰',
  zhipu: '智谱',
  ai_ping: 'AI Ping',
};

export const SUPPORTED_LLM_PROVIDERS = [
  'openai',
  'deepseek',
  'qwen',
  'step',
  'zhipu',
  'ai_ping',
] as const;

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
