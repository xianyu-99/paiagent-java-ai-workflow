import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { NodeConfigProps, OutputParam } from './types';
import { useWorkflowStore } from '../../store/workflowStore';

export const OutputConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const [outputParams, setOutputParams] = useState<OutputParam[]>([]);
  const [responseContent, setResponseContent] = useState('');

  useEffect(() => {
    setOutputParams((node.data?.outputParams as OutputParam[]) || []);
    setResponseContent((node.data?.responseContent as string) || '');
  }, [node]);

  const handleAddOutputParam = () => {
    setOutputParams([...outputParams, { name: '', type: 'input', value: '' }]);
  };

  const handleRemoveOutputParam = (index: number) => {
    setOutputParams(outputParams.filter((_, i) => i !== index));
  };

  const handleUpdateOutputParam = (index: number, field: keyof OutputParam, value: string) => {
    const newParams = [...outputParams];
    if (field === 'type' && value === 'input') {
      newParams[index] = { ...newParams[index], [field]: value, referenceNode: undefined, value: '' };
    } else if (field === 'type' && value === 'reference') {
      newParams[index] = { ...newParams[index], [field]: value, value: '', referenceNode: undefined };
    } else {
      newParams[index] = { ...newParams[index], [field]: value };
    }
    setOutputParams(newParams);
  };

  const commitDraft = useCallback(() => {
    const updatedData = {
      ...node.data,
      outputParams,
      responseContent
    };
    useWorkflowStore.getState().updateNode(node.id, updatedData);
  }, [node.data, node.id, outputParams, responseContent]);

  useEffect(() => {
    registerDraftSaver?.(commitDraft);
  }, [commitDraft, registerDraftSaver]);

  const handleSaveOutputConfig = async () => {
    commitDraft();
    await onSave();
  };

  return (
    <Form layout="vertical" className="mt-4">
      {/* 输出配置 */}
      <div className="mb-6">
        <div className="flex justify-between items-center mb-3">
          <label className="font-medium text-gray-700">输出配置</label>
          <Button 
            type="dashed" 
            size="small" 
            icon={<PlusOutlined />}
            onClick={handleAddOutputParam}
          >
            添加
          </Button>
        </div>
        
        {outputParams.map((param, index) => (
          <div key={index} className="flex items-start gap-2 mb-3">
            <div>
              <Input 
                placeholder="参数名"
                value={param.name}
                onChange={(e) => handleUpdateOutputParam(index, 'name', e.target.value)}
                style={{ width: '100px' }}
              />
            </div>
            <div>
              <Select
                value={param.type}
                onChange={(value: any) => handleUpdateOutputParam(index, 'type', value)}
                style={{ width: '80px' }}
              >
                <Select.Option value="input">输入</Select.Option>
                <Select.Option value="reference">引用</Select.Option>
              </Select>
            </div>
            <div className="flex-1">
              {param.type === 'input' ? (
                <Input 
                  placeholder="输入值"
                  value={param.value}
                  onChange={(e) => handleUpdateOutputParam(index, 'value', e.target.value)}
                />
              ) : (
                <Select
                  placeholder="选择参数"
                  value={param.referenceNode}
                  onChange={(value: any) => handleUpdateOutputParam(index, 'referenceNode', value)}
                  className="w-full"
                >
                  {getReferenceableParams().map(p => (
                    <Select.Option key={p.value} value={p.value}>
                      {p.label}
                    </Select.Option>
                  ))}
                </Select>
              )}
            </div>
            <Button 
              type="text" 
              danger 
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => handleRemoveOutputParam(index)}
            />
          </div>
        ))}
        
        {outputParams.length === 0 && (
          <div className="text-gray-400 text-center py-4 border border-dashed border-gray-300 rounded">
            点击"添加"按钮添加输出参数
          </div>
        )}
      </div>

      {/* 回答内容配置 */}
      <Form.Item label="回答内容配置">
        <Input.TextArea 
          rows={6}
          placeholder="使用 {{参数名}} 引用输出配置中的参数"
          value={responseContent}
          onChange={(e) => setResponseContent(e.target.value)}
        />
        <div className="mt-2 text-xs text-gray-500">
          💡 提示: 使用 {'{{'} 参数名 {'}'} 引用上面定义的参数
        </div>
      </Form.Item>

      <Button type="primary" block onClick={handleSaveOutputConfig}>
        保存配置
      </Button>
    </Form>
  );
};
