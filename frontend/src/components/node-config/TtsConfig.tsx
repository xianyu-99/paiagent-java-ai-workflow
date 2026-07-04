import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button, message } from 'antd';
import { PlusOutlined, DeleteOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { NodeConfigProps, TtsInputParam, TtsOutputParam } from './types';
import { useWorkflowStore } from '../../store/workflowStore';

export const TtsConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const [ttsConfig, setTtsConfig] = useState({
    provider: 'qwen',
    apiUrl: '',
    apiKey: '',
    model: 'qwen3-tts-flash',
    voice: 'Cherry',
    style: '',
    languageType: 'Auto',
    apiKeyConfigured: false
  });
  const [ttsInputParams, setTtsInputParams] = useState<TtsInputParam[]>([]);
  const [ttsOutputParams, setTtsOutputParams] = useState<TtsOutputParam[]>([]);

  useEffect(() => {
    setTtsConfig({
      provider: (node.data?.provider as string) || 'qwen',
      apiUrl: (node.data?.apiUrl as string) || '',
      apiKey: (node.data?.apiKey as string) || '',
      model: (node.data?.model as string) || 'qwen3-tts-flash',
      voice: (node.data?.voice as string) || 'Cherry',
      style: (node.data?.style as string) || '',
      languageType: (node.data?.languageType as string) || 'Auto',
      apiKeyConfigured: Boolean(node.data?.apiKeyConfigured || node.data?.apiKey)
    });
    setTtsInputParams((node.data?.inputParams as TtsInputParam[]) || []);
    setTtsOutputParams((node.data?.outputParams as TtsOutputParam[]) || []);
  }, [node]);

  const handleAddTtsInputParam = () => setTtsInputParams([...ttsInputParams, { name: '', type: 'input', value: '' }]);
  const handleRemoveTtsInputParam = (index: number) => setTtsInputParams(ttsInputParams.filter((_, i) => i !== index));
  const handleUpdateTtsInputParam = (index: number, field: keyof TtsInputParam, value: string) => {
    const newParams = [...ttsInputParams];
    if (field === 'type' && value === 'input') {
      newParams[index] = { ...newParams[index], [field]: value, referenceNode: undefined, value: '' };
    } else if (field === 'type' && value === 'reference') {
      newParams[index] = { ...newParams[index], [field]: value, value: '', referenceNode: undefined };
    } else {
      newParams[index] = { ...newParams[index], [field]: value };
    }
    setTtsInputParams(newParams);
  };

  const handleAddTtsOutputParam = () => setTtsOutputParams([...ttsOutputParams, { name: '', value: '' }]);
  const handleRemoveTtsOutputParam = (index: number) => setTtsOutputParams(ttsOutputParams.filter((_, i) => i !== index));
  const handleUpdateTtsOutputParam = (index: number, field: keyof TtsOutputParam, value: string) => {
    const newParams = [...ttsOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setTtsOutputParams(newParams);
  };

  const commitDraft = useCallback(() => {
    const updatedData = {
      ...node.data,
      provider: ttsConfig.provider,
      apiUrl: ttsConfig.apiUrl,
      apiKey: ttsConfig.apiKey,
      model: ttsConfig.model,
      voice: ttsConfig.voice,
      style: ttsConfig.style,
      languageType: ttsConfig.languageType,
      apiKeyConfigured: Boolean(ttsConfig.apiKey || ttsConfig.apiKeyConfigured),
      inputParams: ttsInputParams,
      outputParams: ttsOutputParams
    };
    useWorkflowStore.getState().updateNode(node.id, updatedData);
  }, [node.data, node.id, ttsConfig, ttsInputParams, ttsOutputParams]);

  const validateConfig = useCallback(() => {
    for (const param of ttsInputParams) {
      if (!param.name) { message.warning('请填写所有输入参数名'); return false; }
      if (param.type === 'input' && !param.value) { message.warning('请填写输入值'); return false; }
      if (param.type === 'reference' && !param.referenceNode) { message.warning('请选择引用参数'); return false; }
    }
    for (const param of ttsOutputParams) {
      if (!param.name) { message.warning('请填写所有输出参数名'); return false; }
    }
    if (!ttsConfig.provider) { message.warning('请选择供应商'); return false; }
    if (!ttsConfig.apiKey && !ttsConfig.apiKeyConfigured) { message.warning('请填写 API Key'); return false; }
    if (!ttsConfig.model) { message.warning('请填写模型名称'); return false; }
    return true;
  }, [ttsConfig, ttsInputParams, ttsOutputParams]);

  const validateAndCommit = useCallback(() => {
    if (!validateConfig()) {
      return false;
    }
    commitDraft();
    return true;
  }, [commitDraft, validateConfig]);

  const saveDraft = useCallback(() => {
    commitDraft();
    return true;
  }, [commitDraft]);

  useEffect(() => {
    registerDraftSaver?.(saveDraft);
  }, [registerDraftSaver, saveDraft]);

  const handleSaveTtsConfig = async () => {
    if (!validateAndCommit()) {
      return;
    }
    await onSave();
  };

  return (
    <Form layout="vertical" className="mt-4">
      {/* 输入配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输入内容配置</label>
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={handleAddTtsInputParam}>添加</Button>
        </div>
        {ttsInputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <Input placeholder="参数名(如 text)" value={param.name} onChange={(e) => handleUpdateTtsInputParam(index, 'name', e.target.value)} style={{ width: '100px' }} />
            <Select value={param.type} onChange={(value: 'input' | 'reference') => handleUpdateTtsInputParam(index, 'type', value)} style={{ width: '80px' }}>
              <Select.Option value="input">输入</Select.Option>
              <Select.Option value="reference">引用</Select.Option>
            </Select>
            <div className="flex-1">
              {param.type === 'input' ? (
                <Input placeholder="输入值" value={param.value} onChange={(e) => handleUpdateTtsInputParam(index, 'value', e.target.value)} />
              ) : (
                <Select placeholder="选择参数" value={param.referenceNode} onChange={(value: string) => handleUpdateTtsInputParam(index, 'referenceNode', value)} className="w-full">
                  {getReferenceableParams().map(p => <Select.Option key={p.value} value={p.value}>{p.label}</Select.Option>)}
                </Select>
              )}
            </div>
            <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleRemoveTtsInputParam(index)} />
          </div>
        ))}
      </div>

      <Form.Item label="API 密钥">
        <Input.Password
          placeholder={ttsConfig.apiKeyConfigured ? "已配置（留空保持不变）" : "输入 API Key"}
          value={ttsConfig.apiKey}
          onChange={(e) => setTtsConfig({...ttsConfig, apiKey: e.target.value})}
        />
      </Form.Item>

      <div className="grid grid-cols-2 gap-3">
        <Form.Item label="供应商">
          <Select value={ttsConfig.provider} disabled>
            <Select.Option value="qwen">阿里云大模型 (Qwen)</Select.Option>
          </Select>
        </Form.Item>
        <Form.Item label="模型名称">
          <Select value={ttsConfig.model} onChange={(val) => setTtsConfig({...ttsConfig, model: val})}>
            <Select.Option value="qwen3-tts-flash">qwen3-tts-flash</Select.Option>
          </Select>
        </Form.Item>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Form.Item label={<span>发音人 <a href="https://help.aliyun.com/zh/model-studio/developer-reference/voice-synthesis-model" target="_blank" rel="noreferrer"><InfoCircleOutlined /></a></span>}>
          <Select value={ttsConfig.voice} onChange={(val) => setTtsConfig({...ttsConfig, voice: val})}>
            <Select.Option value="Cherry">Cherry (女，超拟人)</Select.Option>
            <Select.Option value="Maomao">Maomao (女，幼态)</Select.Option>
            <Select.Option value="Jacket">Jacket (男，青年)</Select.Option>
            <Select.Option value="Huihui">Huihui (女，情感饱满)</Select.Option>
          </Select>
        </Form.Item>
        <Form.Item label="语系">
          <Select value={ttsConfig.languageType} onChange={(val) => setTtsConfig({...ttsConfig, languageType: val})}>
            <Select.Option value="Auto">Auto (自动识别)</Select.Option>
            <Select.Option value="Chinese">Chinese (中文)</Select.Option>
            <Select.Option value="English">English (英文)</Select.Option>
          </Select>
        </Form.Item>
      </div>

      <Form.Item label="发音风格 (可选)">
        <Input placeholder="输入发音风格，如 'cheerful', 'sad', 'angry'" value={ttsConfig.style} onChange={(e) => setTtsConfig({...ttsConfig, style: e.target.value})} />
        <div className="text-xs text-gray-500 mt-1">留空表示使用默认情感。需模型和发音人支持。</div>
      </Form.Item>

      {/* 输出配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输出配置</label>
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={handleAddTtsOutputParam}>添加</Button>
        </div>
        {ttsOutputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <Input placeholder="参数名(如 audioUrl)" value={param.name} onChange={(e) => handleUpdateTtsOutputParam(index, 'name', e.target.value)} style={{ width: '100px' }} />
            <Input placeholder="取值/说明" value={param.value} onChange={(e) => handleUpdateTtsOutputParam(index, 'value', e.target.value)} className="flex-1" />
            <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleRemoveTtsOutputParam(index)} />
          </div>
        ))}
      </div>

      <Button type="primary" block onClick={handleSaveTtsConfig}>
        保存配置
      </Button>
    </Form>
  );
};
