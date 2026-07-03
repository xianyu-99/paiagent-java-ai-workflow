import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button, message, Space, Card, Divider } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { NodeConfigProps } from './types';
import { useWorkflowStore } from '../../store/workflowStore';

export const MediaConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const [config, setConfig] = useState({
    provider: 'openai',
    apiUrl: 'https://api.openai.com/v1/images/generations',
    apiKey: '',
    model: 'dall-e-3',
    resolution: '1024x1024',
    mediaType: 'image'
  });

  const [inputParams, setInputParams] = useState<{ name: string; type: string; value: string; referenceNode?: string }[]>([]);

  useEffect(() => {
    if (node.data) {
      setConfig({
        provider: (node.data.provider as string) || 'openai',
        apiUrl: (node.data.apiUrl as string) || 'https://api.openai.com/v1/images/generations',
        apiKey: (node.data.apiKey as string) || '',
        model: (node.data.model as string) || 'dall-e-3',
        resolution: (node.data.resolution as string) || '1024x1024',
        mediaType: (node.data.mediaType as string) || 'image'
      });
      setInputParams((node.data.inputParams as any) || []);
    }
  }, [node]);

  const commitDraft = useCallback(() => {
    useWorkflowStore.getState().updateNode(node.id, {
      ...node.data,
      ...config,
      inputParams
    });
  }, [config, inputParams, node.data, node.id]);

  useEffect(() => {
    registerDraftSaver?.(commitDraft);
  }, [commitDraft, registerDraftSaver]);

  const handleSave = async () => {
    if (!config.apiKey) {
      message.warning('请输入 API Key');
      return;
    }

    commitDraft();
    await onSave();
  };

  const addInputParam = () => setInputParams([...inputParams, { name: 'prompt', type: 'reference', value: '' }]);
  const removeInputParam = (index: number) => setInputParams(inputParams.filter((_, i) => i !== index));

  return (
    <div className="flex flex-col h-full bg-gray-50">
      <div className="p-4 border-b border-gray-200 bg-white shadow-sm sticky top-0 z-10 flex justify-between items-center">
        <h3 className="font-bold text-gray-800 m-0 text-lg flex items-center">
          <span className="mr-2">🎬</span> 媒体生成配置
        </h3>
        <Button type="primary" onClick={handleSave} size="middle" className="shadow-sm">保存配置</Button>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        <Card size="small" title="基础配置" className="mb-4 shadow-sm border-gray-200">
          <Form layout="vertical" size="small">
            <Form.Item label="API 供应商">
              <Select value={config.provider} onChange={(val) => setConfig({...config, provider: val})}>
                <Select.Option value="openai">OpenAI (DALL-E 3)</Select.Option>
                <Select.Option value="luma">Luma (Dream Machine)</Select.Option>
                <Select.Option value="runway">Runway</Select.Option>
                <Select.Option value="kling">可灵 (Kling)</Select.Option>
                <Select.Option value="custom">自定义</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item label="API URL">
              <Input value={config.apiUrl} onChange={(e) => setConfig({...config, apiUrl: e.target.value})} />
            </Form.Item>

            <Form.Item label="API Key" required>
              <Input.Password value={config.apiKey} onChange={(e) => setConfig({...config, apiKey: e.target.value})} placeholder="输入 API 密钥" />
            </Form.Item>

            <Form.Item label="模型名称">
              <Input value={config.model} onChange={(e) => setConfig({...config, model: e.target.value})} placeholder="例如: dall-e-3" />
            </Form.Item>

            <Form.Item label="生成类型">
              <Select value={config.mediaType} onChange={(val) => setConfig({...config, mediaType: val})}>
                <Select.Option value="image">图像 (Image)</Select.Option>
                <Select.Option value="video">视频 (Video)</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item label="分辨率 / 比例">
              <Input value={config.resolution} onChange={(e) => setConfig({...config, resolution: e.target.value})} placeholder="例如: 1024x1024 或 16:9" />
            </Form.Item>
          </Form>
        </Card>

        <Card 
          size="small" 
          title="输入参数" 
          extra={<Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addInputParam}>添加</Button>}
          className="mb-4 shadow-sm border-gray-200"
        >
          {inputParams.length === 0 ? (
            <div className="text-gray-400 text-center py-4 text-xs">暂无输入参数配置，默认使用上游节点的 output 作为 prompt</div>
          ) : (
            <div className="space-y-3">
              {inputParams.map((param, index) => (
                <div key={index} className="flex flex-col gap-2 p-3 bg-gray-50 rounded border border-gray-200">
                  <div className="flex gap-2">
                    <Input placeholder="参数名 (如 prompt)" value={param.name} onChange={(e) => {
                      const newParams = [...inputParams];
                      newParams[index].name = e.target.value;
                      setInputParams(newParams);
                    }} style={{ width: '100px' }} />
                    <Select value={param.type} onChange={(value) => {
                      const newParams = [...inputParams];
                      newParams[index].type = value;
                      setInputParams(newParams);
                    }} style={{ width: '80px' }}>
                      <Select.Option value="input">固定值</Select.Option>
                      <Select.Option value="reference">引用值</Select.Option>
                    </Select>
                    
                    {param.type === 'input' ? (
                      <Input className="flex-1" placeholder="输入值" value={param.value} onChange={(e) => {
                        const newParams = [...inputParams];
                        newParams[index].value = e.target.value;
                        setInputParams(newParams);
                      }} />
                    ) : (
                      <Select className="flex-1" placeholder="选择引用的节点参数" value={param.referenceNode} onChange={(value) => {
                        const newParams = [...inputParams];
                        newParams[index].referenceNode = value;
                        setInputParams(newParams);
                      }} options={getReferenceableParams()} />
                    )}
                    
                    <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeInputParam(index)} />
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
};
