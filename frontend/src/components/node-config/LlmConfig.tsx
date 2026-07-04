import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Form, Input, Select, Button, message } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { NodeConfigProps, LlmInputParam, LlmOutputParam } from './types';
import { useWorkflowStore } from '../../store/workflowStore';
import { useLLMConfigStore } from '../../store/llmConfigStore';
import {
  SUPPORTED_LLM_PROVIDERS,
  normalizeProviderKey,
  getProviderFromNodeType,
  getProviderLabel,
  getProviderDefaultBaseUrl,
  getProviderModelPlaceholder,
} from '../../utils/provider';
import SkillSelector from '../SkillSelector';

const DEFAULT_PROVIDER_MODELS: Record<string, string> = {
  openai: 'gpt-4o-mini',
  deepseek: 'deepseek-chat',
  qwen: 'qwen-plus',
  moonshot: 'kimi-k2.6',
  kimi_code: 'kimi-for-coding',
  mimo: 'mimo-v2.5-pro',
};

export const LlmConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const llmGlobalConfigs = useLLMConfigStore(state => state.configs);
  const fetchLLMGlobalConfigs = useLLMConfigStore(state => state.fetchAllConfigs);

  const [llmConfig, setLlmConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    temperature: 0.7,
    prompt: '',
    skillName: ''
  });
  const [llmInputParams, setLlmInputParams] = useState<LlmInputParam[]>([]);
  const [llmOutputParams, setLlmOutputParams] = useState<LlmOutputParam[]>([]);
  const initializedNodeIdRef = useRef<string | null>(null);
  const dirtyRef = useRef(false);

  const updateLlmConfig = useCallback((nextConfig: typeof llmConfig) => {
    dirtyRef.current = true;
    setLlmConfig(nextConfig);
  }, []);

  useEffect(() => {
    fetchLLMGlobalConfigs();
  }, [fetchLLMGlobalConfigs]);

  useEffect(() => {
    if (initializedNodeIdRef.current === node.id && dirtyRef.current) {
      return;
    }

    const configId = (node.data?.configId as number) || undefined;
    const matchedGlobalConfig = configId
      ? llmGlobalConfigs.find(c => c.id === configId)
      : undefined;
    const provider = normalizeProviderKey(
      matchedGlobalConfig?.provider ||
      String(node.data?.provider || '') ||
      getProviderFromNodeType(String(node.data?.type || ''))
    );
    setLlmConfig({
      provider,
      configId,
      apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
      apiKey: configId ? '' : (node.data?.apiKey as string) || '',
      model: matchedGlobalConfig?.model || (node.data?.model as string) || '',
      temperature: matchedGlobalConfig?.temperature ?? (node.data?.temperature as number | undefined) ?? 0.7,
      prompt: (node.data?.prompt as string) || (node.data?.systemPrompt as string) || '',
      skillName: (node.data?.skillName as string) || ''
    });
    setLlmInputParams((node.data?.inputParams as LlmInputParam[]) || []);
    setLlmOutputParams((node.data?.outputParams as LlmOutputParam[]) || []);
    initializedNodeIdRef.current = node.id;
    dirtyRef.current = false;
  }, [node, llmGlobalConfigs]);

  const handleAddLlmInputParam = () => {
    dirtyRef.current = true;
    setLlmInputParams([...llmInputParams, { name: '', type: 'input', value: '' }]);
  };
  const handleRemoveLlmInputParam = (index: number) => {
    dirtyRef.current = true;
    setLlmInputParams(llmInputParams.filter((_, i) => i !== index));
  };
  const handleUpdateLlmInputParam = (index: number, field: keyof LlmInputParam, value: string) => {
    dirtyRef.current = true;
    const newParams = [...llmInputParams];
    if (field === 'type' && value === 'input') {
      newParams[index] = { ...newParams[index], [field]: value, referenceNode: undefined, value: '' };
    } else if (field === 'type' && value === 'reference') {
      newParams[index] = { ...newParams[index], [field]: value, value: '', referenceNode: undefined };
    } else {
      newParams[index] = { ...newParams[index], [field]: value };
    }
    setLlmInputParams(newParams);
  };

  const handleAddLlmOutputParam = () => {
    dirtyRef.current = true;
    setLlmOutputParams([...llmOutputParams, { name: '', type: 'string', description: '' }]);
  };
  const handleRemoveLlmOutputParam = (index: number) => {
    dirtyRef.current = true;
    setLlmOutputParams(llmOutputParams.filter((_, i) => i !== index));
  };
  const handleUpdateLlmOutputParam = (index: number, field: keyof LlmOutputParam, value: string) => {
    dirtyRef.current = true;
    const newParams = [...llmOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmOutputParams(newParams);
  };

  const shouldReplaceProviderDefault = (currentValue: string, previousProvider: string, defaultResolver: (provider: string) => string) => {
    if (!currentValue) return true;
    if (Boolean(previousProvider) && currentValue === defaultResolver(previousProvider)) return true;
    return SUPPORTED_LLM_PROVIDERS.some((provider: string) => currentValue === defaultResolver(provider));
  };

  const commitDraft = useCallback(() => {
    const useGlobalConfig = !!llmConfig.configId;
    const updatedData = {
      ...node.data,
      provider: llmConfig.provider,
      configId: llmConfig.configId,
      apiUrl: useGlobalConfig ? '' : llmConfig.apiUrl,
      apiKey: useGlobalConfig ? '' : llmConfig.apiKey,
      model: useGlobalConfig ? '' : llmConfig.model,
      temperature: useGlobalConfig ? 0.7 : llmConfig.temperature,
      prompt: llmConfig.prompt,
      systemPrompt: node.data?.type === 'agent' ? llmConfig.prompt : undefined,
      skillName: llmConfig.skillName,
      inputParams: llmInputParams,
      outputParams: llmOutputParams
    };
    useWorkflowStore.getState().updateNode(node.id, updatedData);
  }, [llmConfig, llmInputParams, llmOutputParams, node.data, node.id]);

  const validateConfig = useCallback(() => {
    for (const param of llmInputParams) {
      if (!param.name) { message.warning('请填写所有参数名'); return false; }
      if (param.type === 'input' && !param.value) { message.warning('请填写输入值'); return false; }
      if (param.type === 'reference' && !param.referenceNode) { message.warning('请选择引用参数'); return false; }
    }
    if (!llmConfig.prompt) { message.warning('请填写提示词模板'); return false; }

    const paramNames = new Set(llmInputParams.map(p => p.name));
    const templateParamRegex = /\{\{(\w+)\}\}/g;
    const matches = llmConfig.prompt.matchAll(templateParamRegex);
    const undefinedParams: string[] = [];
    for (const match of matches) {
      if (!paramNames.has(match[1])) undefinedParams.push(match[1]);
    }
    if (undefinedParams.length > 0) {
      message.warning(`提示词模板中引用了未定义的参数: ${undefinedParams.join(', ')}`);
      return false;
    }

    if (!llmConfig.configId) {
      if (!llmConfig.provider) { message.warning('请选择供应商'); return false; }
      if (!llmConfig.apiUrl) { message.warning('请填写 API 地址'); return false; }
      if (!llmConfig.apiKey) { message.warning('请填写 API 密钥'); return false; }
      if (!llmConfig.model) { message.warning('请填写模型名称'); return false; }
    }

    return true;
  }, [llmConfig, llmInputParams]);

  const validateAndCommit = useCallback(() => {
    if (!validateConfig()) {
      return false;
    }
    commitDraft();
    dirtyRef.current = false;
    return true;
  }, [commitDraft, validateConfig]);

  const saveDraft = useCallback(() => {
    commitDraft();
    dirtyRef.current = false;
    return true;
  }, [commitDraft]);

  useEffect(() => {
    registerDraftSaver?.(saveDraft);
  }, [registerDraftSaver, saveDraft]);

  const handleSaveConfig = async () => {
    if (!validateAndCommit()) {
      return;
    }
    await onSave();
  };

  const selectedNodeType = String(node?.data?.type || '');
  const isGenericLlmNode = selectedNodeType === 'llm';
  const selectedNodeProvider = getProviderFromNodeType(selectedNodeType);

  const availableLlmConfigs = isGenericLlmNode
    ? llmGlobalConfigs
    : llmGlobalConfigs.filter((config: { provider: string, configName: string, id: number }) => normalizeProviderKey(config.provider) === selectedNodeProvider);

  const providerOptions = SUPPORTED_LLM_PROVIDERS.map(p => ({ value: p, label: getProviderLabel(p) }));

  return (
    <Form layout="vertical" className="mt-4">
      {/* 输入参数配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输入参数</label>
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={handleAddLlmInputParam}>添加</Button>
        </div>
        {llmInputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <Input placeholder="参数名" value={param.name} onChange={(e) => handleUpdateLlmInputParam(index, 'name', e.target.value)} style={{ width: '90px' }} />
            <Select value={param.type} onChange={(value: 'input' | 'reference') => handleUpdateLlmInputParam(index, 'type', value)} style={{ width: '70px' }}>
              <Select.Option value="input">输入</Select.Option>
              <Select.Option value="reference">引用</Select.Option>
            </Select>
            <div className="flex-1">
              {param.type === 'input' ? (
                <Input placeholder="输入值" value={param.value} onChange={(e) => handleUpdateLlmInputParam(index, 'value', e.target.value)} />
              ) : (
                <Select placeholder="选择参数" value={param.referenceNode} onChange={(value: string) => handleUpdateLlmInputParam(index, 'referenceNode', value)} className="w-full">
                  {getReferenceableParams().map(p => <Select.Option key={p.value} value={p.value}>{p.label}</Select.Option>)}
                </Select>
              )}
            </div>
            <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleRemoveLlmInputParam(index)} />
          </div>
        ))}
      </div>

      {/* 输出参数配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输出参数</label>
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={handleAddLlmOutputParam}>添加</Button>
        </div>
        {llmOutputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <Input placeholder="变量名" value={param.name} onChange={(e) => handleUpdateLlmOutputParam(index, 'name', e.target.value)} style={{ width: '100px' }} />
            <Input value="string" disabled style={{ width: '70px' }} />
            <div className="flex-1">
              <Input placeholder="描述（可选）" value={param.description} onChange={(e) => handleUpdateLlmOutputParam(index, 'description', e.target.value)} />
            </div>
            <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleRemoveLlmOutputParam(index)} />
          </div>
        ))}
      </div>

      <Form.Item label="提示词模板" required>
        <Input.TextArea
          rows={12}
          placeholder="输入提示词模板，使用 {{参数名}} 引用输入参数"
          value={llmConfig.prompt}
          onChange={(e) => updateLlmConfig({...llmConfig, prompt: e.target.value})}
          style={{ fontFamily: 'monospace', fontSize: '12px' }}
        />
      </Form.Item>

      <Form.Item label="全局配置">
        <Select
          value={llmConfig.configId}
          onChange={(value: number) => {
            if (value) {
              const config = llmGlobalConfigs.find(c => c.id === value);
              if (config) {
                updateLlmConfig({
                  ...llmConfig,
                  provider: normalizeProviderKey(config.provider),
                  configId: value,
                  apiUrl: config.apiUrl,
                  apiKey: '',
                  model: config.model,
                  temperature: config.temperature
                });
              }
            } else {
              updateLlmConfig({
                ...llmConfig,
                provider: isGenericLlmNode ? '' : selectedNodeProvider,
                configId: undefined,
                apiUrl: '', apiKey: '', model: '', temperature: 0.7
              });
            }
          }}
          placeholder="选择一个全局配置" allowClear
        >
          {availableLlmConfigs.map(config => (
            <Select.Option key={config.id} value={config.id}>
              {isGenericLlmNode ? `${getProviderLabel(config.provider)} / ` : ''}{config.configName}
            </Select.Option>
          ))}
        </Select>
      </Form.Item>

      {!llmConfig.configId && (
        <>
          {isGenericLlmNode && (
            <Form.Item label="供应商" required>
              <Select
                value={llmConfig.provider || undefined}
                options={providerOptions} allowClear
                onChange={(value: string) => {
                  if (!value) {
                    updateLlmConfig({ ...llmConfig, provider: '', apiUrl: '', model: '' });
                    return;
                  }
                  const previousProvider = llmConfig.provider;
                  const defaultBaseUrl = getProviderDefaultBaseUrl(value);
                  const defaultModel = DEFAULT_PROVIDER_MODELS[value] || '';
                  updateLlmConfig({
                    ...llmConfig,
                    provider: value,
                    apiUrl: shouldReplaceProviderDefault(llmConfig.apiUrl, previousProvider, getProviderDefaultBaseUrl) ? defaultBaseUrl : llmConfig.apiUrl,
                    model: shouldReplaceProviderDefault(llmConfig.model, previousProvider, (p: string) => DEFAULT_PROVIDER_MODELS[p] || '') ? defaultModel : llmConfig.model
                  });
                }}
              />
            </Form.Item>
          )}
          <Form.Item label="API 地址" required>
            <Input value={llmConfig.apiUrl} onChange={(e) => updateLlmConfig({...llmConfig, apiUrl: e.target.value})} />
          </Form.Item>
          <Form.Item label="API 密钥" required>
            <Input.Password value={llmConfig.apiKey} onChange={(e) => updateLlmConfig({...llmConfig, apiKey: e.target.value})} />
          </Form.Item>
          <Form.Item label="模型名称" required>
            <Input placeholder={getProviderModelPlaceholder(llmConfig.provider)} value={llmConfig.model} onChange={(e) => updateLlmConfig({...llmConfig, model: e.target.value})} />
          </Form.Item>
          <Form.Item label="温度">
            <Input
              type="number"
              step="0.1"
              min="0"
              max="2"
              value={llmConfig.temperature}
              onChange={(e) => {
                const nextTemperature = parseFloat(e.target.value);
                updateLlmConfig({...llmConfig, temperature: Number.isNaN(nextTemperature) ? 0.7 : nextTemperature});
              }}
            />
          </Form.Item>
        </>
      )}

      {llmConfig.configId && (
        <div className="text-xs text-gray-500 mb-4 p-3 bg-gray-50 rounded">
          <div>供应商: {getProviderLabel(llmConfig.provider)}</div>
          <div>API 地址: {llmConfig.apiUrl}</div>
          <div>模型: {llmConfig.model}</div>
          <div>温度: {llmConfig.temperature}</div>
        </div>
      )}

      <Form.Item label="技能 (Skill)">
        <SkillSelector value={llmConfig.skillName} onChange={(value) => updateLlmConfig({...llmConfig, skillName: value || ''})} />
      </Form.Item>

      <Button type="primary" block onClick={handleSaveConfig}>
        保存配置
      </Button>
    </Form>
  );
};
