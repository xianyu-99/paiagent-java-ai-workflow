import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Select, Button, message, Checkbox, Tabs, Upload } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { NodeConfigProps, RagConfig as RagConfigType } from './types';
import { useWorkflowStore } from '../../store/workflowStore';
import { useLLMConfigStore } from '../../store/llmConfigStore';
import {
  KnowledgeBase,
  listKnowledgeBases,
  createKnowledgeBase,
  uploadKnowledgeDocument,
  uploadKnowledgeFile,
  rebuildKnowledgeBaseEmbeddings
} from '../../api/knowledge';
import {
  getProviderLabel,
  normalizeProviderKey,
} from '../../utils/provider';

const MAX_KNOWLEDGE_UPLOAD_SIZE_MB = 50;
const MAX_KNOWLEDGE_UPLOAD_SIZE = MAX_KNOWLEDGE_UPLOAD_SIZE_MB * 1024 * 1024;

export const RagConfig: React.FC<NodeConfigProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const llmGlobalConfigs = useLLMConfigStore(state => state.configs);
  const fetchLLMGlobalConfigs = useLLMConfigStore(state => state.fetchAllConfigs);

  const [ragConfig, setRagConfig] = useState<RagConfigType>({
    knowledgeBaseId: undefined,
    configId: undefined,
    retrievalOnly: false,
    questionReference: 'input-default.input',
    topK: 3,
    minScore: 0,
    contextWindow: 1,
    contextMaxChars: 1800,
    prompt: ''
  });

  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [newKnowledgeBaseName, setNewKnowledgeBaseName] = useState('');
  const [newKnowledgeBaseDescription, setNewKnowledgeBaseDescription] = useState('');
  
  const [knowledgeFileName, setKnowledgeFileName] = useState('');
  const [knowledgeContent, setKnowledgeContent] = useState('');
  const [knowledgeLocalFile, setKnowledgeLocalFile] = useState<File | null>(null);
  const [knowledgeFileUploading, setKnowledgeFileUploading] = useState(false);
  const [knowledgeReindexing, setKnowledgeReindexing] = useState(false);

  const refreshKnowledgeBases = useCallback(async () => {
    const res = await listKnowledgeBases();
    if (res.code === 200) setKnowledgeBases(res.data);
  }, []);

  useEffect(() => {
    fetchLLMGlobalConfigs();
    refreshKnowledgeBases();
  }, [fetchLLMGlobalConfigs, refreshKnowledgeBases]);

  useEffect(() => {
    const inputParams = (node.data?.inputParams as { name: string; referenceNode?: string }[]) || [];
    const questionParam = inputParams.find(param => param.name === 'question');
    setRagConfig({
      knowledgeBaseId: (node.data?.knowledgeBaseId as number) || undefined,
      configId: (node.data?.configId as number) || undefined,
      retrievalOnly: Boolean(node.data?.retrievalOnly),
      questionReference: questionParam?.referenceNode || 'input-default.input',
      topK: (node.data?.topK as number) || 3,
      minScore: (node.data?.minScore as number) || 0,
      contextWindow: (node.data?.contextWindow as number) ?? 1,
      contextMaxChars: (node.data?.contextMaxChars as number) || 1800,
      prompt: (node.data?.prompt as string) || ''
    });
  }, [node]);

  const handleCreateKnowledgeBase = async () => {
    if (!newKnowledgeBaseName.trim()) { message.warning('请填写知识库名称'); return; }
    const response = await createKnowledgeBase({
      name: newKnowledgeBaseName.trim(),
      description: newKnowledgeBaseDescription.trim()
    });
    if (response.code === 200) {
      message.success('知识库创建成功');
      setNewKnowledgeBaseName('');
      setNewKnowledgeBaseDescription('');
      await refreshKnowledgeBases();
      setRagConfig({ ...ragConfig, knowledgeBaseId: response.data.id });
    } else {
      message.error(response.message || '知识库创建失败');
    }
  };

  const handleUploadKnowledgeDocument = async () => {
    if (!ragConfig.knowledgeBaseId) { message.warning('请先选择知识库'); return; }
    if (!knowledgeContent.trim()) { message.warning('请填写要导入的知识文本'); return; }
    const response = await uploadKnowledgeDocument(ragConfig.knowledgeBaseId, {
      fileName: knowledgeFileName || 'manual.txt',
      content: knowledgeContent
    });
    if (response.code === 200) {
      message.success(`文档导入成功，生成 ${response.data.chunkCount} 个切片`);
      setKnowledgeContent('');
      await refreshKnowledgeBases();
    } else {
      message.error(response.message || '文档导入失败');
    }
  };

  const handleUploadKnowledgeLocalFile = async () => {
    if (!ragConfig.knowledgeBaseId) { message.warning('请先选择知识库'); return; }
    if (!knowledgeLocalFile) { message.warning('请选择本地 txt / markdown 文件'); return; }
    if (knowledgeLocalFile.size > MAX_KNOWLEDGE_UPLOAD_SIZE) {
      message.error(`文件不能超过 ${MAX_KNOWLEDGE_UPLOAD_SIZE_MB}MB`);
      return;
    }

    setKnowledgeFileUploading(true);
    try {
      const response = await uploadKnowledgeFile(ragConfig.knowledgeBaseId, knowledgeLocalFile);
      if (response.code === 200) {
        message.success(`文件上传成功，生成 ${response.data.chunkCount} 个切片`);
        setKnowledgeLocalFile(null);
        await refreshKnowledgeBases();
      } else {
        message.error(response.message || '文件上传失败');
      }
    } finally {
      setKnowledgeFileUploading(false);
    }
  };

  const handleRebuildKnowledgeEmbeddings = async () => {
    if (!ragConfig.knowledgeBaseId) { message.warning('请先选择知识库'); return; }
    setKnowledgeReindexing(true);
    try {
      const response = await rebuildKnowledgeBaseEmbeddings(ragConfig.knowledgeBaseId);
      if (response.code === 200) {
        const result = response.data;
        message.success(`向量索引重建完成：${result.chunkCount} 个切片，${result.embeddingProvider}/${result.embeddingModel}`);
        await refreshKnowledgeBases();
      } else {
        message.error(response.message || '重建向量索引失败');
      }
    } finally {
      setKnowledgeReindexing(false);
    }
  };

  const commitDraft = useCallback(() => {
    let provider = '';
    let apiUrl = '';
    const apiKey = '';
    let model = '';
    let temperature = Number.NaN;

    if (ragConfig.configId) {
      const config = llmGlobalConfigs.find(c => c.id === ragConfig.configId);
      if (config) {
        provider = normalizeProviderKey(config.provider);
        apiUrl = config.apiUrl;
        model = config.model;
        temperature = config.temperature;
      }
    }

    if (!provider) provider = (node.data?.provider as string) || '';
    if (!apiUrl) apiUrl = (node.data?.apiUrl as string) || '';
    if (!model) model = (node.data?.model as string) || '';
    if (!Number.isFinite(temperature)) temperature = (node.data?.temperature as number | undefined) ?? 0.7;

    const updatedData = {
      ...node.data,
      type: 'rag',
      knowledgeBaseId: ragConfig.knowledgeBaseId,
      configId: ragConfig.configId,
      provider,
      apiUrl,
      apiKey,
      model,
      temperature,
      retrievalOnly: ragConfig.retrievalOnly,
      topK: ragConfig.topK,
      minScore: ragConfig.minScore,
      contextWindow: ragConfig.contextWindow,
      contextMaxChars: ragConfig.contextMaxChars,
      prompt: ragConfig.prompt,
      inputParams: [
        {
          name: 'question',
          type: 'reference',
          referenceNode: ragConfig.questionReference
        }
      ],
      outputParams: [{ name: 'output', type: 'string' }]
    };
    useWorkflowStore.getState().updateNode(node.id, updatedData);
  }, [llmGlobalConfigs, node.data, node.id, ragConfig]);

  const validateConfig = useCallback(() => {
    if (!ragConfig.knowledgeBaseId) { message.warning('请选择知识库'); return false; }
    if (!ragConfig.retrievalOnly && !ragConfig.configId) {
      message.warning('请选择全局大模型配置（用于总结回答）'); return false;
    }
    if (!ragConfig.questionReference) {
      message.warning('请选择问题来源');
      return false;
    }
    return true;
  }, [ragConfig]);

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

  const handleSaveRagConfig = async () => {
    if (!validateAndCommit()) {
      return;
    }
    await onSave();
  };

  return (
    <Form layout="vertical" className="mt-4">
      <div className="mb-4 p-3 bg-blue-50 rounded text-sm text-blue-700">
        RAG 节点可只检索企业知识库片段，也可继续调用 LLM 生成答案。
      </div>

      <Form.Item label="知识库" required>
        <Select
          placeholder="选择知识库"
          value={ragConfig.knowledgeBaseId}
          onChange={(value: number) => setRagConfig({ ...ragConfig, knowledgeBaseId: value })}
        >
          {knowledgeBases.map(kb => (
            <Select.Option key={kb.id} value={kb.id}>
              {kb.name}（文档 {kb.documentCount || 0} / 切片 {kb.chunkCount || 0}）
            </Select.Option>
          ))}
        </Select>
      </Form.Item>

      <div className="mb-4 p-3 border rounded bg-gray-50">
        <div className="font-medium text-gray-700 mb-2">新建知识库</div>
        <Input className="mb-2" placeholder="知识库名称" value={newKnowledgeBaseName} onChange={(e) => setNewKnowledgeBaseName(e.target.value)} />
        <Input className="mb-2" placeholder="知识库描述，可选" value={newKnowledgeBaseDescription} onChange={(e) => setNewKnowledgeBaseDescription(e.target.value)} />
        <Button block onClick={handleCreateKnowledgeBase}>创建并选中</Button>
      </div>

      <div className="mb-4 p-3 border rounded bg-gray-50">
        <div className="font-medium text-gray-700 mb-2">导入知识</div>
        <Tabs
          size="small"
          items={[
            {
              key: 'paste',
              label: '粘贴文本',
              children: (
                <>
                  <Input className="mb-2" placeholder="文件名，如 faq.md" value={knowledgeFileName} onChange={(e) => setKnowledgeFileName(e.target.value)} />
                  <Input.TextArea rows={5} placeholder="粘贴 txt / markdown 文本，后端会自动切片并向量化" value={knowledgeContent} onChange={(e) => setKnowledgeContent(e.target.value)} />
                  <Button className="mt-2" block onClick={handleUploadKnowledgeDocument}>上传并切片</Button>
                </>
              )
            },
            {
              key: 'file',
              label: '本地文件',
              children: (
                <>
                  <Upload
                    accept=".txt,.md,.markdown,.json,.pdf,.doc,.docx"
                    maxCount={1}
                    beforeUpload={(file) => {
                      if (file.size > MAX_KNOWLEDGE_UPLOAD_SIZE) {
                        message.error(`文件不能超过 ${MAX_KNOWLEDGE_UPLOAD_SIZE_MB}MB`);
                        return Upload.LIST_IGNORE;
                      }
                      setKnowledgeLocalFile(file);
                      return false;
                    }}
                    onRemove={() => setKnowledgeLocalFile(null)}
                    fileList={knowledgeLocalFile ? [{ uid: knowledgeLocalFile.name, name: knowledgeLocalFile.name, status: 'done' }] : []}
                  >
                    <Button icon={<UploadOutlined />} block>选择本地 txt / md / json / pdf / doc / docx 文件</Button>
                  </Upload>
                  <Button className="mt-2" block loading={knowledgeFileUploading} onClick={handleUploadKnowledgeLocalFile}>上传文件并切片</Button>
                </>
              )
            }
          ]}
        />
        <Button className="mt-2" block loading={knowledgeReindexing} onClick={handleRebuildKnowledgeEmbeddings}>重建向量索引</Button>
      </div>

      <Form.Item label="问题来源" required>
        <Select
          placeholder="选择用户问题来源"
          value={ragConfig.questionReference}
          onChange={(value: string) => setRagConfig({ ...ragConfig, questionReference: value })}
        >
          {getReferenceableParams().map((p: { label: string, value: string }) => (
            <Select.Option key={p.value} value={p.value}>{p.label}</Select.Option>
          ))}
        </Select>
      </Form.Item>

      <Form.Item>
        <Checkbox checked={ragConfig.retrievalOnly} onChange={(e) => setRagConfig({ ...ragConfig, retrievalOnly: e.target.checked })}>
          仅执行检索，不调用大模型生成回答
        </Checkbox>
        <div className="text-xs text-gray-500 mt-1">
          开启后：节点输出检索到的片段（数组）；关闭后：LLM 基于片段生成最终答案并输出。
        </div>
      </Form.Item>

      <div className="grid grid-cols-2 gap-4">
        <Form.Item label="Top-K 召回">
          <Input type="number" min={1} max={20} value={ragConfig.topK} onChange={(e) => setRagConfig({ ...ragConfig, topK: parseInt(e.target.value) || 3 })} />
        </Form.Item>
        <Form.Item label="最低相似度得分">
          <Input type="number" step="0.05" min={0} max={1} value={ragConfig.minScore} onChange={(e) => setRagConfig({ ...ragConfig, minScore: parseFloat(e.target.value) || 0 })} />
        </Form.Item>
      </div>
      
      <div className="grid grid-cols-2 gap-4">
        <Form.Item label="上下文窗口大小">
          <Input type="number" min={0} max={5} value={ragConfig.contextWindow} onChange={(e) => setRagConfig({ ...ragConfig, contextWindow: parseInt(e.target.value) ?? 1 })} />
          <div className="text-xs text-gray-500 mt-1">设为1即带出匹配块的前一块和后一块</div>
        </Form.Item>
        <Form.Item label="拼装最大字符数">
          <Input type="number" min={500} max={8000} value={ragConfig.contextMaxChars} onChange={(e) => setRagConfig({ ...ragConfig, contextMaxChars: parseInt(e.target.value) || 1800 })} />
          <div className="text-xs text-gray-500 mt-1">若拼出的上下文超过此数则截断</div>
        </Form.Item>
      </div>

      {!ragConfig.retrievalOnly && (
        <>
          <Form.Item label="LLM 总结模型" required>
            <Select
              value={ragConfig.configId}
              onChange={(value: number) => setRagConfig({ ...ragConfig, configId: value })}
              placeholder="选择一个全局模型配置"
              allowClear
            >
              {llmGlobalConfigs.map(config => (
                <Select.Option key={config.id} value={config.id}>
                  {getProviderLabel(config.provider)} / {config.configName}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item label="总结提示词模板" required>
            <Input.TextArea
              rows={6}
              placeholder="自定义总结提示词，{{context}} 为检索内容，{{question}} 为用户问题。"
              value={ragConfig.prompt}
              onChange={(e) => setRagConfig({ ...ragConfig, prompt: e.target.value })}
              style={{ fontFamily: 'monospace', fontSize: '12px' }}
            />
          </Form.Item>
        </>
      )}

      <Button type="primary" block onClick={handleSaveRagConfig}>
        保存配置
      </Button>
    </Form>
  );
};
