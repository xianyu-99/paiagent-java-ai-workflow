import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button, message } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { NodeConfigProps, LlmInputParam, LlmOutputParam } from './types';
import { useWorkflowStore } from '../../store/workflowStore';
import { useLLMConfigStore } from '../../store/llmConfigStore';
import {
  SUPPORTED_LLM_PROVIDERS,
  normalizeProviderKey,
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

// 后置可选工具列表（按需扩充）
const AVAILABLE_TOOLS: { label: string; value: string }[] = [];

export const AgentConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const llmGlobalConfigs = useLLMConfigStore(state => state.configs);
  const fetchLLMGlobalConfigs = useLLMConfigStore(state => state.fetchAllConfigs);

  const [llmConfig, setLlmConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    temperature: 0.7,
    systemPrompt: '',
    skillName: '',
    tools: [] as string[],
    maxIterations: 5,
    reasoningMode: 'react'
  });
  const [llmInputParams, setLlmInputParams] = useState<LlmInputParam[]>([]);
  const [llmOutputParams, setLlmOutputParams] = useState<LlmOutputParam[]>([]);

  useEffect(() => {
    fetchLLMGlobalConfigs();
  }, [fetchLLMGlobalConfigs]);

  useEffect(() => {
    const configId = (node.data?.configId as number) || undefined;
    const matchedGlobalConfig = configId
      ? llmGlobalConfigs.find(c => c.id === configId)
      : undefined;
    
    // Agent 是特殊的 llm 类型
    const provider = normalizeProviderKey(
      matchedGlobalConfig?.provider || String(node.data?.provider || '')
    );
    
    setLlmConfig({
      provider,
      configId,
      apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
      apiKey: configId ? '' : (node.data?.apiKey as string) || '',
      model: matchedGlobalConfig?.model || (node.data?.model as string) || '',
      temperature: matchedGlobalConfig?.temperature || (node.data?.temperature as number) || 0.7,
      systemPrompt: (node.data?.systemPrompt as string) || (node.data?.prompt as string) || '',
      skillName: (node.data?.skillName as string) || '',
      tools: Array.isArray(node.data?.tools) ? node.data.tools : [],
      maxIterations: (node.data?.maxIterations as number) || 5,
      reasoningMode: (node.data?.reasoningMode as string) || 'react'
    });
    setLlmInputParams((node.data?.inputParams as LlmInputParam[]) || []);
    setLlmOutputParams((node.data?.outputParams as LlmOutputParam[]) || []);
  }, [node, llmGlobalConfigs]);

  const handleAddLlmInputParam = () => setLlmInputParams([...llmInputParams, { name: '', type: 'input', value: '' }]);
  const handleRemoveLlmInputParam = (index: number) => setLlmInputParams(llmInputParams.filter((_, i) => i !== index));
  const handleUpdateLlmInputParam = (index: number, field: keyof LlmInputParam, value: string) => {
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

  const handleAddLlmOutputParam = () => setLlmOutputParams([...llmOutputParams, { name: '', type: 'string', description: '' }]);
  const handleRemoveLlmOutputParam = (index: number) => setLlmOutputParams(llmOutputParams.filter((_, i) => i !== index));
  const handleUpdateLlmOutputParam = (index: number, field: keyof LlmOutputParam, value: string) => {
    const newParams = [...llmOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmOutputParams(newParams);
  };

  const shouldReplaceProviderDefault = (currentValue: string, previousProvider: string, defaultResolver: (provider: string) => string) => {
    if (!currentValue) return true;
    if (Boolean(previousProvider) && currentValue === defaultResolver(previousProvider)) return true;
    return SUPPORTED_LLM_PROVIDERS.some((provider: any) => currentValue === defaultResolver(provider));
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
      systemPrompt: llmConfig.systemPrompt,
      prompt: llmConfig.systemPrompt,
      skillName: llmConfig.skillName,
      tools: llmConfig.tools,
      maxIterations: llmConfig.maxIterations,
      reasoningMode: llmConfig.reasoningMode,
      inputParams: llmInputParams,
      outputParams: llmOutputParams
    };
    useWorkflowStore.getState().updateNode(node.id, updatedData);
  }, [llmConfig, llmInputParams, llmOutputParams, node.data, node.id]);

  useEffect(() => {
    registerDraftSaver?.(commitDraft);
  }, [commitDraft, registerDraftSaver]);

  const handleSaveConfig = async () => {
    // 验证逻辑
    for (const param of llmInputParams) {
      if (!param.name) { message.warning('请填写所有参数名'); return; }
      if (param.type === 'input' && !param.value) { message.warning('请填写输入值'); return; }
      if (param.type === 'reference' && !param.referenceNode) { message.warning('请选择引用参数'); return; }
    }
    if (!llmConfig.systemPrompt) { message.warning('请填写 Agent 的系统提示词'); return; }

    if (!llmConfig.configId) {
      if (!llmConfig.provider) { message.warning('请选择供应商'); return; }
      if (!llmConfig.apiUrl) { message.warning('请填写 API 地址'); return; }
      if (!llmConfig.apiKey) { message.warning('请填写 API 密钥'); return; }
      if (!llmConfig.model) { message.warning('请填写模型名称'); return; }
    }

    commitDraft();
    await onSave();
  };

  const providerOptions = SUPPORTED_LLM_PROVIDERS.map(p => ({ value: p, label: getProviderLabel(p) }));

  return (
    <Form layout="vertical" className="mt-4">
      {/* 智能体专属配置 */}
      <div className="mb-6 p-4 bg-blue-50 border border-blue-100 rounded-lg">
        <h4 className="font-semibold text-blue-800 mb-4">🕵️ 智能体能力配置</h4>
        
        <Form.Item label="挂载工具 (Tools)">
          <Select
            mode="multiple"
            allowClear
            placeholder="请选择智能体可调用的工具"
            value={llmConfig.tools}
            onChange={(val) => setLlmConfig({...llmConfig, tools: val})}
            options={AVAILABLE_TOOLS}
          />
        </Form.Item>
        
        <div className="grid grid-cols-2 gap-4">
          <Form.Item label="推理模式">
            <Select 
              value={llmConfig.reasoningMode}
              onChange={(val) => setLlmConfig({...llmConfig, reasoningMode: val})}
            >
              <Select.Option value="react">ReAct (默认)</Select.Option>
              <Select.Option value="plan_and_execute">Plan & Execute</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item label="最大迭代次数">
            <Input 
              type="number"
              min={1}
              max={20}
              value={llmConfig.maxIterations}
              onChange={(e) => setLlmConfig({...llmConfig, maxIterations: parseInt(e.target.value) || 5})}
            />
          </Form.Item>
        </div>
      </div>

      {/* 输入参数配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输入参数</label>
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={handleAddLlmInputParam}>添加</Button>
        </div>
        {llmInputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <Input placeholder="参数名" value={param.name} onChange={(e) => handleUpdateLlmInputParam(index, 'name', e.target.value)} style={{ width: '90px' }} />
            <Select value={param.type} onChange={(value: any) => handleUpdateLlmInputParam(index, 'type', value)} style={{ width: '70px' }}>
              <Select.Option value="input">输入</Select.Option>
              <Select.Option value="reference">引用</Select.Option>
            </Select>
            <div className="flex-1">
              {param.type === 'input' ? (
                <Input placeholder="输入值" value={param.value} onChange={(e) => handleUpdateLlmInputParam(index, 'value', e.target.value)} />
              ) : (
                <Select placeholder="选择参数" value={param.referenceNode} onChange={(value: any) => handleUpdateLlmInputParam(index, 'referenceNode', value)} className="w-full">
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

      <Form.Item label="系统提示词 (System Prompt)" required>
        <Input.TextArea
          rows={8}
          placeholder="给 Agent 的核心设定与规则指令..."
          value={llmConfig.systemPrompt}
          onChange={(e) => setLlmConfig({...llmConfig, systemPrompt: e.target.value})}
          style={{ fontFamily: 'monospace', fontSize: '12px' }}
        />
      </Form.Item>

      <Form.Item label="全局模型配置">
        <Select
          value={llmConfig.configId}
          onChange={(value: any) => {
            if (value) {
              const config = llmGlobalConfigs.find(c => c.id === value);
              if (config) {
                setLlmConfig({
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
              setLlmConfig({
                ...llmConfig,
                provider: '',
                configId: undefined,
                apiUrl: '', apiKey: '', model: '', temperature: 0.7
              });
            }
          }}
          placeholder="选择一个全局配置" allowClear
        >
          {llmGlobalConfigs.map(config => (
            <Select.Option key={config.id} value={config.id}>
              {getProviderLabel(config.provider)} / {config.configName}
            </Select.Option>
          ))}
        </Select>
      </Form.Item>

      {!llmConfig.configId && (
        <>
          <Form.Item label="供应商" required>
            <Select
              value={llmConfig.provider || undefined}
              options={providerOptions} allowClear
              onChange={(value: any) => {
                if (!value) {
                  setLlmConfig({ ...llmConfig, provider: '', apiUrl: '', model: '' });
                  return;
                }
                const previousProvider = llmConfig.provider;
                const defaultBaseUrl = getProviderDefaultBaseUrl(value);
                const defaultModel = DEFAULT_PROVIDER_MODELS[value] || '';
                setLlmConfig({
                  ...llmConfig,
                  provider: value,
                  apiUrl: shouldReplaceProviderDefault(llmConfig.apiUrl, previousProvider, getProviderDefaultBaseUrl) ? defaultBaseUrl : llmConfig.apiUrl,
                  model: shouldReplaceProviderDefault(llmConfig.model, previousProvider, (p: any) => DEFAULT_PROVIDER_MODELS[p] || '') ? defaultModel : llmConfig.model
                });
              }}
            />
          </Form.Item>
          
          <Form.Item label="API 地址" required>
            <Input value={llmConfig.apiUrl} onChange={(e) => setLlmConfig({...llmConfig, apiUrl: e.target.value})} />
          </Form.Item>
          <Form.Item label="API 密钥" required>
            <Input.Password value={llmConfig.apiKey} onChange={(e) => setLlmConfig({...llmConfig, apiKey: e.target.value})} />
          </Form.Item>
          <Form.Item label="模型名称" required>
            <Input placeholder={getProviderModelPlaceholder(llmConfig.provider)} value={llmConfig.model} onChange={(e) => setLlmConfig({...llmConfig, model: e.target.value})} />
          </Form.Item>
          <Form.Item label="温度">
            <Input type="number" step="0.1" min="0" max="2" value={llmConfig.temperature} onChange={(e) => setLlmConfig({...llmConfig, temperature: parseFloat(e.target.value) || 0.7})} />
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
        <SkillSelector value={llmConfig.skillName} onChange={(value: any) => setLlmConfig({...llmConfig, skillName: value || ''})} />
      </Form.Item>

      <Button type="primary" block onClick={handleSaveConfig}>
        保存配置
      </Button>
    </Form>
  );
};
