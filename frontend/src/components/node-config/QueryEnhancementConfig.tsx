import React, { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, Select, message } from 'antd';
import { NodeConfigProps } from './types';
import { useWorkflowStore } from '../../store/workflowStore';
import { useLLMConfigStore } from '../../store/llmConfigStore';
import {
  getProviderDefaultBaseUrl,
  getProviderLabel,
  getProviderModelPlaceholder,
  getSupportedProviderOptions,
  normalizeProviderKey,
} from '../../utils/provider';

const DEFAULT_PROVIDER_MODELS: Record<string, string> = {
  openai: 'gpt-4o-mini',
  deepseek: 'deepseek-chat',
  qwen: 'qwen-plus',
  moonshot: 'kimi-k2.6',
  kimi_code: 'kimi-for-coding',
  mimo: 'mimo-v2.5-pro',
};

export const QueryEnhancementConfig: React.FC<NodeConfigProps> = ({ node, onSave, registerDraftSaver }) => {
  const llmGlobalConfigs = useLLMConfigStore(state => state.configs);
  const fetchLLMGlobalConfigs = useLLMConfigStore(state => state.fetchAllConfigs);
  const nodeType = String(node.data?.type || '');
  const isQueryExpansion = nodeType === 'query_expansion';

  const [config, setConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    temperature: 0.2,
    expansionCount: 3,
  });

  useEffect(() => {
    fetchLLMGlobalConfigs();
  }, [fetchLLMGlobalConfigs]);

  useEffect(() => {
    const configId = (node.data?.configId as number) || undefined;
    const matchedGlobalConfig = configId
      ? llmGlobalConfigs.find(c => c.id === configId)
      : undefined;
    const provider = normalizeProviderKey(
      matchedGlobalConfig?.provider || String(node.data?.provider || '')
    );

    setConfig({
      provider,
      configId,
      apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
      apiKey: configId ? '' : (node.data?.apiKey as string) || '',
      model: matchedGlobalConfig?.model || (node.data?.model as string) || '',
      temperature: (node.data?.temperature as number) ?? matchedGlobalConfig?.temperature ?? 0.2,
      expansionCount: (node.data?.expansionCount as number) || 3,
    });
  }, [node, llmGlobalConfigs]);

  const commitDraft = useCallback(() => {
    const useGlobalConfig = !!config.configId;
    useWorkflowStore.getState().updateNode(node.id, {
      ...node.data,
      provider: config.provider,
      configId: config.configId,
      apiUrl: useGlobalConfig ? '' : config.apiUrl,
      apiKey: useGlobalConfig ? '' : config.apiKey,
      model: useGlobalConfig ? '' : config.model,
      temperature: useGlobalConfig ? 0.2 : config.temperature,
      ...(isQueryExpansion ? { expansionCount: config.expansionCount } : {}),
      outputParams: isQueryExpansion
        ? [{ name: 'expandedQueries', type: 'array' }, { name: 'output', type: 'string' }]
        : [{ name: 'hydeQuery', type: 'string' }, { name: 'output', type: 'string' }],
    });
  }, [config, isQueryExpansion, node.data, node.id]);

  useEffect(() => {
    registerDraftSaver?.(commitDraft);
  }, [commitDraft, registerDraftSaver]);

  const handleSave = async () => {
    if (!config.configId) {
      if (!config.provider) { message.warning('请选择供应商'); return; }
      if (!config.apiUrl) { message.warning('请填写 API 地址'); return; }
      if (!config.apiKey) { message.warning('请填写 API 密钥'); return; }
      if (!config.model) { message.warning('请填写模型名称'); return; }
    }
    commitDraft();
    await onSave();
  };

  return (
    <Form layout="vertical" className="mt-4">
      <div className="mb-4 p-3 bg-blue-50 rounded text-sm text-blue-700">
        {isQueryExpansion
          ? '查询扩展会先生成多个等价问法，供下游检索链路使用。'
          : 'HyDE 会先生成假设性回答，并把它作为下游 RAG 的默认检索文本。'}
      </div>

      <Form.Item label="全局模型配置">
        <Select
          allowClear
          placeholder="选择已保存的模型配置，或清空后手动填写"
          value={config.configId}
          onChange={(value?: number) => {
            if (!value) {
              setConfig({ ...config, configId: undefined });
              return;
            }
            const selected = llmGlobalConfigs.find(item => item.id === value);
            setConfig({
              ...config,
              configId: value,
              provider: normalizeProviderKey(selected?.provider || config.provider),
              apiUrl: selected?.apiUrl || config.apiUrl,
              apiKey: '',
              model: selected?.model || config.model,
              temperature: selected?.temperature ?? config.temperature,
            });
          }}
        >
          {llmGlobalConfigs.map(item => (
            <Select.Option key={item.id} value={item.id}>
              {item.configName} - {getProviderLabel(item.provider)} / {item.model}
            </Select.Option>
          ))}
        </Select>
      </Form.Item>

      {!config.configId && (
        <>
          <Form.Item label="供应商" required>
            <Select
              placeholder="选择供应商"
              options={getSupportedProviderOptions()}
              value={config.provider || undefined}
              onChange={(provider: string) => {
                setConfig({
                  ...config,
                  provider,
                  apiUrl: getProviderDefaultBaseUrl(provider),
                  model: DEFAULT_PROVIDER_MODELS[provider] || config.model,
                });
              }}
            />
          </Form.Item>
          <Form.Item label="API 地址" required>
            <Input value={config.apiUrl} onChange={(e) => setConfig({ ...config, apiUrl: e.target.value })} />
          </Form.Item>
          <Form.Item label="API 密钥" required>
            <Input.Password value={config.apiKey} onChange={(e) => setConfig({ ...config, apiKey: e.target.value })} />
          </Form.Item>
          <Form.Item label="模型名称" required>
            <Input
              placeholder={getProviderModelPlaceholder(config.provider)}
              value={config.model}
              onChange={(e) => setConfig({ ...config, model: e.target.value })}
            />
          </Form.Item>
          <Form.Item label="温度">
            <Input
              type="number"
              step="0.1"
              min="0"
              max="2"
              value={config.temperature}
              onChange={(e) => setConfig({ ...config, temperature: parseFloat(e.target.value) || 0.2 })}
            />
          </Form.Item>
        </>
      )}

      {isQueryExpansion && (
        <Form.Item label="扩展数量">
          <Input
            type="number"
            min="1"
            max="10"
            value={config.expansionCount}
            onChange={(e) => setConfig({ ...config, expansionCount: parseInt(e.target.value, 10) || 3 })}
          />
        </Form.Item>
      )}

      <Button type="primary" block onClick={handleSave}>
        保存配置
      </Button>
    </Form>
  );
};
