import React, { useEffect, useState } from 'react';
import {
  Modal,
  Form,
  Input,
  InputNumber,
  AutoComplete,
  Button,
  Table,
  Space,
  message,
  Popconfirm,
  Tag
} from 'antd';
import { SettingOutlined, PlusOutlined, EditOutlined, DeleteOutlined, StarOutlined, StarFilled } from '@ant-design/icons';
import { useLLMConfigStore } from '../store/llmConfigStore';
import { LLMGlobalConfig, LLMConfigRequest } from '../api/llmConfig';
import { getProviderLabel, normalizeProviderKey } from '../utils/provider';

const PROVIDERS = [
  { value: 'openai', label: 'OpenAI' },
  { value: 'deepseek', label: 'DeepSeek' },
  { value: 'qwen', label: '通义千问' },
  { value: 'step', label: '阶跃星辰' }
];

interface LLMConfigModalProps {
  visible?: boolean;
  onClose?: () => void;
}

const LLMConfigModal: React.FC<LLMConfigModalProps> = ({ visible, onClose }) => {
  const [modalVisible, setModalVisible] = useState(visible || false);
  const [formVisible, setFormVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState<LLMGlobalConfig | null>(null);
  const [form] = Form.useForm();

  const {
    configs,
    loading,
    error,
    fetchAllConfigs,
    saveConfig,
    deleteConfig,
    setDefaultConfig,
    clearError
  } = useLLMConfigStore();

  const providerOptions = Array.from(
    new Map(
      [...PROVIDERS, ...configs.map((config) => ({
        value: normalizeProviderKey(config.provider),
        label: getProviderLabel(config.provider)
      }))]
        .map((item) => [normalizeProviderKey(item.value), item])
    ).values()
  ).map((item) => ({
    value: normalizeProviderKey(item.value),
    label: item.label
  }));

  useEffect(() => {
    if (modalVisible) {
      fetchAllConfigs();
    }
  }, [modalVisible, fetchAllConfigs]);

  useEffect(() => {
    if (visible !== undefined) {
      setModalVisible(visible);
    }
  }, [visible]);

  useEffect(() => {
    if (error) {
      message.error(error);
      clearError();
    }
  }, [error, clearError]);

  const handleOpenModal = () => {
    setModalVisible(true);
  };

  const handleCloseModal = () => {
    setModalVisible(false);
    setFormVisible(false);
    setEditingConfig(null);
    form.resetFields();
    onClose?.();
  };

  const handleOpenForm = (config?: LLMGlobalConfig) => {
    setEditingConfig(config || null);
    setFormVisible(true);
    if (config) {
      form.setFieldsValue({
        provider: config.provider,
        configName: config.configName,
        apiUrl: config.apiUrl,
        apiKey: '',
        model: config.model,
        temperature: config.temperature
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ temperature: 0.7 });
    }
  };

  const handleCloseForm = () => {
    setFormVisible(false);
    setEditingConfig(null);
    form.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const provider = normalizeProviderKey(values.provider);
      const apiKey = values.apiKey?.trim();
      const request: LLMConfigRequest = {
        id: editingConfig?.id,
        provider,
        configName: values.configName,
        apiUrl: values.apiUrl,
        apiKey: apiKey || undefined,
        model: values.model,
        temperature: values.temperature
      };

      const result = await saveConfig(request);
      if (result) {
        message.success(editingConfig ? '配置已更新' : '配置已创建');
        handleCloseForm();
      }
    } catch (error) {
      console.error('Form validation failed:', error);
    }
  };

  const handleDelete = async (id: number) => {
    const success = await deleteConfig(id);
    if (success) {
      message.success('配置已删除');
    }
  };

  const handleSetDefault = async (id: number) => {
    const success = await setDefaultConfig(id);
    if (success) {
      message.success('已设置为默认配置');
    }
  };

  const columns = [
    {
      title: '提供商',
      dataIndex: 'provider',
      key: 'provider',
      ellipsis: true,
      width: 88,
      render: (provider: string) => {
        return getProviderLabel(provider);
      }
    },
    {
      title: '配置别名',
      dataIndex: 'configName',
      key: 'configName',
      ellipsis: true,
      width: 132
    },
    {
      title: 'API 地址',
      dataIndex: 'apiUrl',
      key: 'apiUrl',
      ellipsis: true,
      width: 210
    },
    {
      title: '模型',
      dataIndex: 'model',
      key: 'model',
      width: 170
    },
    {
      title: '温度',
      dataIndex: 'temperature',
      key: 'temperature',
      width: 64
    },
    {
      title: '供应商默认',
      dataIndex: 'isDefault',
      key: 'isDefault',
      width: 108,
      render: (isDefault: number) =>
        isDefault === 1 ? (
          <Tag color="gold" icon={<StarFilled />}>
            默认
          </Tag>
        ) : null
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_value: unknown, record: LLMGlobalConfig) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleOpenForm(record)}
          >
            编辑
          </Button>
          {record.isDefault !== 1 && (
            <Button
              type="link"
              size="small"
              icon={<StarOutlined />}
              onClick={() => handleSetDefault(record.id)}
            >
              设为该供应商默认
            </Button>
          )}
          <Popconfirm
            title="确定要删除此配置吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <>
      {!visible && (
        <Button
          type="text"
          icon={<SettingOutlined />}
          onClick={handleOpenModal}
          title="LLM 配置管理"
        />
      )}

      <Modal
        title="全局 LLM 配置管理"
        open={modalVisible}
        onCancel={handleCloseModal}
        footer={null}
        width={1100}
        style={{ maxWidth: 'calc(100vw - 32px)' }}
      >
        <div className="mb-4">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => handleOpenForm()}
          >
            新增配置
          </Button>
          <div className="text-xs text-gray-500 mt-2">
            默认配置按供应商分别生效，每个供应商会有一条默认配置。
          </div>
        </div>

        <Table
          columns={columns}
          dataSource={configs}
          rowKey="id"
          loading={loading}
          pagination={false}
          size="small"
          tableLayout="fixed"
          scroll={{ x: 980 }}
        />

        <Modal
          title={editingConfig ? '编辑配置' : '新增配置'}
          open={formVisible}
          onCancel={handleCloseForm}
          onOk={handleSubmit}
          confirmLoading={loading}
          okText="保存"
          cancelText="取消"
        >
          <Form
            form={form}
            layout="vertical"
            autoComplete="off"
          >
            <Form.Item
              name="provider"
              label="提供商"
              rules={[
                { required: true, message: '请输入或选择提供商' },
                {
                  validator: (_, value) =>
                    value?.trim()
                      ? Promise.resolve()
                      : Promise.reject(new Error('请输入或选择提供商'))
                }
              ]}
              extra="支持直接手填，也可以从已有供应商中选择。手填时建议与节点类型保持一致，例如 openai、deepseek、qwen、step。"
            >
              <AutoComplete
                placeholder="请输入或选择提供商"
                options={providerOptions}
                disabled={!!editingConfig}
                filterOption={(inputValue, option) =>
                  (option?.value ?? '').toLowerCase().includes(inputValue.toLowerCase()) ||
                  String(option?.label ?? '').toLowerCase().includes(inputValue.toLowerCase())
                }
              />
            </Form.Item>

            <Form.Item
              name="configName"
              label="配置别名"
              rules={[{ required: true, message: '请输入配置别名' }]}
              extra="给这套配置起一个方便识别的名字，比如“默认 OpenAI”“便宜模型”“主账号”。"
            >
              <Input placeholder="例如: 默认 OpenAI、主账号、便宜模型" />
            </Form.Item>

            <Form.Item
              name="apiUrl"
              label="API 地址"
              rules={[{ required: true, message: '请输入 API 地址' }]}
              extra="填写接口根地址，不要追加具体接口路径。"
            >
              <Input placeholder="例如: https://api.openai.com" />
            </Form.Item>

            <Form.Item
              name="apiKey"
              label="API 密钥"
              rules={editingConfig ? [] : [{ required: true, message: '请输入 API 密钥' }]}
              extra={editingConfig ? '留空则沿用已保存的 API Key。' : undefined}
            >
              <Input.Password placeholder={editingConfig ? '留空则不修改 API Key' : '输入 API 密钥'} />
            </Form.Item>

            <Form.Item
              name="model"
              label="默认模型"
              rules={[{ required: true, message: '请输入默认模型' }]}
            >
              <Input placeholder="例如: gpt-3.5-turbo, deepseek-chat, claude-3-5-sonnet-20241022" />
            </Form.Item>

            <Form.Item
              name="temperature"
              label="温度"
              tooltip="控制输出的随机性，值越小越确定，值越大越随机"
            >
              <InputNumber
                min={0}
                max={2}
                step={0.1}
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Form>
        </Modal>
      </Modal>
    </>
  );
};

export default LLMConfigModal;
