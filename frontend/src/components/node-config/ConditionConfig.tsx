import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button, Checkbox, message } from 'antd';
import { NodeConfigProps, ConditionConfig as ConditionConfigType } from './types';
import { useWorkflowStore } from '../../store/workflowStore';

export const ConditionConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const [conditionConfig, setConditionConfig] = useState<ConditionConfigType>({
    leftType: 'reference',
    leftValue: '',
    leftReference: 'input-default.input',
    operator: 'contains',
    rightValue: '',
    caseSensitive: false
  });

  useEffect(() => {
    setConditionConfig({
      leftType: (node.data?.leftType as 'input' | 'reference') || 'reference',
      leftValue: (node.data?.leftValue as string) || '',
      leftReference: (node.data?.leftReference as string) || 'input-default.input',
      operator: (node.data?.operator as string) || 'contains',
      rightValue: (node.data?.rightValue as string) || '',
      caseSensitive: Boolean(node.data?.caseSensitive)
    });
  }, [node]);

  const commitDraft = useCallback(() => {
    useWorkflowStore.getState().updateNode(node.id, {
      ...node.data,
      leftType: conditionConfig.leftType,
      leftValue: conditionConfig.leftValue,
      leftReference: conditionConfig.leftReference,
      operator: conditionConfig.operator,
      rightValue: conditionConfig.rightValue,
      caseSensitive: conditionConfig.caseSensitive
    });
  }, [conditionConfig, node.data, node.id]);

  const validateConfig = useCallback(() => {
    if (conditionConfig.leftType === 'reference' && !conditionConfig.leftReference) {
      message.warning('请选择左侧引用参数');
      return false;
    }
    if (conditionConfig.leftType === 'input' && !conditionConfig.leftValue) {
      message.warning('请填写左侧固定值');
      return false;
    }
    if (!['empty', 'not_empty'].includes(conditionConfig.operator) && !conditionConfig.rightValue) {
      message.warning('请填写右侧比较值');
      return false;
    }
    return true;
  }, [conditionConfig]);

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

  const handleSaveConditionConfig = async () => {
    if (!validateAndCommit()) {
      return;
    }
    await onSave();
  };

  return (
    <Form layout="vertical" className="mt-4">
      <div className="mb-4 p-3 bg-orange-50 rounded text-sm text-orange-700">
        条件成立时走 <strong>true</strong> 出口，不成立时走 <strong>false</strong> 出口。
      </div>

      <Form.Item label="左侧值来源" required>
        <Select
          value={conditionConfig.leftType}
          onChange={(value: 'input' | 'reference') => setConditionConfig({ ...conditionConfig, leftType: value })}
        >
          <Select.Option value="reference">引用上游参数</Select.Option>
          <Select.Option value="input">固定值</Select.Option>
        </Select>
      </Form.Item>

      {conditionConfig.leftType === 'reference' ? (
        <Form.Item label="左侧引用参数" required>
          <Select
            placeholder="选择要判断的上游输出"
            value={conditionConfig.leftReference}
            onChange={(value: string) => setConditionConfig({ ...conditionConfig, leftReference: value })}
            style={{ width: '100%' }}
          >
            {[{ label: '运行时.loopIteration', value: 'loopIteration' }, ...getReferenceableParams()].map((p: { label: string, value: string }) => (
              <Select.Option key={p.value} value={p.value}>
                {p.label}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>
      ) : (
        <Form.Item label="左侧固定值" required>
          <Input
            placeholder="输入左侧比较值"
            value={conditionConfig.leftValue}
            onChange={(e) => setConditionConfig({ ...conditionConfig, leftValue: e.target.value })}
          />
        </Form.Item>
      )}

      <Form.Item label="判断条件" required>
        <Select
          value={conditionConfig.operator}
          onChange={(value: string) => setConditionConfig({ ...conditionConfig, operator: value })}
        >
          <Select.Option value="equals">等于</Select.Option>
          <Select.Option value="not_equals">不等于</Select.Option>
          <Select.Option value="contains">包含</Select.Option>
          <Select.Option value="not_contains">不包含</Select.Option>
          <Select.Option value="starts_with">以此开头</Select.Option>
          <Select.Option value="ends_with">以此结尾</Select.Option>
          <Select.Option value="empty">为空</Select.Option>
          <Select.Option value="not_empty">不为空</Select.Option>
          <Select.Option value="gt">大于</Select.Option>
          <Select.Option value="gte">大于等于</Select.Option>
          <Select.Option value="lt">小于</Select.Option>
          <Select.Option value="lte">小于等于</Select.Option>
        </Select>
      </Form.Item>

      {!['empty', 'not_empty'].includes(conditionConfig.operator) && (
        <Form.Item label="右侧比较值" required>
          <Input
            placeholder="输入右侧比较值"
            value={conditionConfig.rightValue}
            onChange={(e) => setConditionConfig({ ...conditionConfig, rightValue: e.target.value })}
          />
        </Form.Item>
      )}

      <Form.Item>
        <Checkbox
          checked={conditionConfig.caseSensitive}
          onChange={(e) => setConditionConfig({ ...conditionConfig, caseSensitive: e.target.checked })}
        >
          区分大小写
        </Checkbox>
      </Form.Item>

      <div className="mb-4 text-xs text-gray-500 bg-gray-50 rounded p-3">
        连接方式：从条件节点右侧绿色 handle 连 true 分支，红色 handle 连 false 分支。
      </div>

      <Button type="primary" block onClick={handleSaveConditionConfig}>
        保存配置
      </Button>
    </Form>
  );
};
