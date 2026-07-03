import React, { useEffect } from 'react';
import { Form, Input, Checkbox } from 'antd';
import { NodeConfigProps } from './types';

export const InputConfig: React.FC<NodeConfigProps> = ({ registerDraftSaver }) => {
  useEffect(() => {
    registerDraftSaver?.(null);
  }, [registerDraftSaver]);

  return (
    <Form layout="vertical" className="mt-4">
      <Form.Item label="变量名">
        <Input value="user_input" disabled />
      </Form.Item>
      <Form.Item label="变量类型">
        <Input value="String" disabled />
      </Form.Item>
      <Form.Item label="描述">
        <Input.TextArea value="用户本轮的输入内容" disabled rows={2} />
      </Form.Item>
      <Form.Item label="是否必要">
        <Checkbox checked disabled>必要</Checkbox>
      </Form.Item>
    </Form>
  );
};
