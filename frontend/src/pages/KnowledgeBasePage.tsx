import { useEffect, useState } from 'react';
import { Button, Card, Empty, Input, List, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Upload, message } from 'antd';
import { ArrowLeftOutlined, DeleteOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  KnowledgeBase,
  KnowledgeChunk,
  KnowledgeDocument,
  listKnowledgeBases,
  listKnowledgeChunks,
  listKnowledgeDocuments,
  rebuildKnowledgeBaseEmbeddings,
  uploadKnowledgeDocument,
  uploadKnowledgeFile,
} from '../api/knowledge';

const KnowledgeBasePage = () => {
  const navigate = useNavigate();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedKnowledgeBaseId, setSelectedKnowledgeBaseId] = useState<number>();
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [chunks, setChunks] = useState<KnowledgeChunk[]>([]);
  const [chunksModalOpen, setChunksModalOpen] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [pasteFileName, setPasteFileName] = useState('manual.md');
  const [pasteContent, setPasteContent] = useState('');
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [reindexing, setReindexing] = useState(false);

  const selectedKnowledgeBase = knowledgeBases.find((kb) => kb.id === selectedKnowledgeBaseId);

  const refreshKnowledgeBases = async () => {
    setLoading(true);
    try {
      const response = await listKnowledgeBases();
      if (response.code === 200) {
        const list = response.data || [];
        setKnowledgeBases(list);
        if (!selectedKnowledgeBaseId && list.length > 0) {
          setSelectedKnowledgeBaseId(list[0].id);
        }
      } else {
        message.error(response.message || '知识库加载失败');
      }
    } finally {
      setLoading(false);
    }
  };

  const refreshDocuments = async (knowledgeBaseId?: number) => {
    if (!knowledgeBaseId) {
      setDocuments([]);
      return;
    }
    const response = await listKnowledgeDocuments(knowledgeBaseId);
    if (response.code === 200) {
      setDocuments(response.data || []);
    } else {
      message.error(response.message || '文档加载失败');
    }
  };

  useEffect(() => {
    refreshKnowledgeBases();
  }, []);

  useEffect(() => {
    refreshDocuments(selectedKnowledgeBaseId);
  }, [selectedKnowledgeBaseId]);

  const handleCreateKnowledgeBase = async () => {
    if (!newName.trim()) {
      message.warning('请填写知识库名称');
      return;
    }
    const response = await createKnowledgeBase({
      name: newName.trim(),
      description: newDescription.trim(),
    });
    if (response.code === 200) {
      message.success('知识库创建成功');
      setNewName('');
      setNewDescription('');
      await refreshKnowledgeBases();
      setSelectedKnowledgeBaseId(response.data.id);
    } else {
      message.error(response.message || '知识库创建失败');
    }
  };

  const handleDeleteKnowledgeBase = async () => {
    if (!selectedKnowledgeBaseId) return;
    const response = await deleteKnowledgeBase(selectedKnowledgeBaseId);
    if (response.code === 200) {
      message.success('知识库已删除');
      setSelectedKnowledgeBaseId(undefined);
      setDocuments([]);
      await refreshKnowledgeBases();
    } else {
      message.error(response.message || '删除失败');
    }
  };

  const handlePasteUpload = async () => {
    if (!selectedKnowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    if (!pasteContent.trim()) {
      message.warning('请粘贴知识文本');
      return;
    }
    setUploading(true);
    try {
      const response = await uploadKnowledgeDocument(selectedKnowledgeBaseId, {
        fileName: pasteFileName || 'manual.md',
        content: pasteContent,
      });
      if (response.code === 200) {
        message.success(`导入成功，生成 ${response.data.chunkCount} 个 chunk`);
        setPasteContent('');
        await refreshKnowledgeBases();
        await refreshDocuments(selectedKnowledgeBaseId);
      } else {
        message.error(response.message || '导入失败');
      }
    } finally {
      setUploading(false);
    }
  };

  const handleFileUpload = async () => {
    if (!selectedKnowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    if (!localFile) {
      message.warning('请选择本地文件');
      return;
    }
    setUploading(true);
    try {
      const response = await uploadKnowledgeFile(selectedKnowledgeBaseId, localFile);
      if (response.code === 200) {
        message.success(`文件导入成功，生成 ${response.data.chunkCount} 个 chunk`);
        setLocalFile(null);
        await refreshKnowledgeBases();
        await refreshDocuments(selectedKnowledgeBaseId);
      } else {
        message.error(response.message || '文件导入失败');
      }
    } finally {
      setUploading(false);
    }
  };

  const handleReindex = async () => {
    if (!selectedKnowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    setReindexing(true);
    try {
      const response = await rebuildKnowledgeBaseEmbeddings(selectedKnowledgeBaseId);
      if (response.code === 200) {
        message.success(`向量索引重建完成：${response.data.chunkCount} 个 chunk`);
      } else {
        message.error(response.message || '重建失败');
      }
    } finally {
      setReindexing(false);
    }
  };

  const handleOpenChunks = async (document: KnowledgeDocument) => {
    if (!selectedKnowledgeBaseId) return;
    const response = await listKnowledgeChunks(selectedKnowledgeBaseId, document.id);
    if (response.code === 200) {
      setChunks(response.data || []);
      setChunksModalOpen(true);
    } else {
      message.error(response.message || 'chunk 加载失败');
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="bg-white shadow-sm px-6 py-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/editor')}>
            返回工作流
          </Button>
          <h1 className="text-2xl font-bold text-gray-800 m-0">知识库管理</h1>
        </div>
        <Button icon={<ReloadOutlined />} onClick={refreshKnowledgeBases} loading={loading}>
          刷新
        </Button>
      </div>

      <div className="p-6 grid grid-cols-[320px_1fr] gap-6">
        <Card title="知识库">
          <Space direction="vertical" className="w-full">
            <Input placeholder="知识库名称" value={newName} onChange={(e) => setNewName(e.target.value)} />
            <Input placeholder="描述，可选" value={newDescription} onChange={(e) => setNewDescription(e.target.value)} />
            <Button type="primary" block onClick={handleCreateKnowledgeBase}>
              新建知识库
            </Button>
            <Select
              className="w-full"
              placeholder="选择知识库"
              value={selectedKnowledgeBaseId}
              onChange={setSelectedKnowledgeBaseId}
              options={knowledgeBases.map((kb) => ({
                value: kb.id,
                label: `${kb.name}（文档 ${kb.documentCount || 0} / chunk ${kb.chunkCount || 0}）`,
              }))}
            />
            {selectedKnowledgeBase && (
              <div className="p-3 rounded bg-gray-50 text-sm text-gray-700">
                <div className="font-medium">{selectedKnowledgeBase.name}</div>
                <div>{selectedKnowledgeBase.description || '暂无描述'}</div>
                <div className="mt-2">文档：{selectedKnowledgeBase.documentCount || 0}</div>
                <div>Chunk：{selectedKnowledgeBase.chunkCount || 0}</div>
              </div>
            )}
            <Popconfirm title="确认删除该知识库？" onConfirm={handleDeleteKnowledgeBase}>
              <Button danger block icon={<DeleteOutlined />} disabled={!selectedKnowledgeBaseId}>
                删除知识库
              </Button>
            </Popconfirm>
          </Space>
        </Card>

        <Space direction="vertical" className="w-full" size="large">
          <Card title="导入知识">
            <Tabs
              items={[
                {
                  key: 'paste',
                  label: '粘贴文本',
                  children: (
                    <Space direction="vertical" className="w-full">
                      <Input value={pasteFileName} onChange={(e) => setPasteFileName(e.target.value)} placeholder="文件名，如 faq.md" />
                      <Input.TextArea
                        rows={8}
                        value={pasteContent}
                        onChange={(e) => setPasteContent(e.target.value)}
                        placeholder="粘贴 txt / markdown 文本，后端会按标题、段落和标点切分 chunk"
                      />
                      <Button type="primary" loading={uploading} onClick={handlePasteUpload}>
                        导入粘贴文本
                      </Button>
                    </Space>
                  ),
                },
                {
                  key: 'file',
                  label: '本地文件',
                  children: (
                    <Space direction="vertical" className="w-full">
                      <Upload
                        accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        maxCount={1}
                        beforeUpload={(file) => {
                          setLocalFile(file);
                          return false;
                        }}
                        onRemove={() => setLocalFile(null)}
                        fileList={localFile ? [{ uid: localFile.name, name: localFile.name, status: 'done' }] : []}
                      >
                        <Button icon={<UploadOutlined />}>选择 txt / md / pdf / docx 文件</Button>
                      </Upload>
                      <Button type="primary" loading={uploading} onClick={handleFileUpload}>
                        上传文件并切片
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          </Card>

          <Card
            title="文档与 Chunk"
            extra={
              <Button onClick={handleReindex} loading={reindexing} disabled={!selectedKnowledgeBaseId}>
                重建向量索引
              </Button>
            }
          >
            {documents.length === 0 ? (
              <Empty description="暂无文档" />
            ) : (
              <Table
                rowKey="id"
                dataSource={documents}
                pagination={false}
                columns={[
                  { title: '文件名', dataIndex: 'fileName' },
                  {
                    title: '类型',
                    dataIndex: 'contentType',
                    render: (value: string) => value ? <Tag>{value}</Tag> : '-',
                  },
                  {
                    title: '解析器',
                    dataIndex: 'parserType',
                    render: (value: string) => value ? <Tag color="blue">{value}</Tag> : '-',
                  },
                  { title: 'Chunk 数', dataIndex: 'chunkCount', width: 100 },
                  { title: '创建时间', dataIndex: 'createdAt', width: 180 },
                  {
                    title: '操作',
                    width: 120,
                    render: (_, record) => (
                      <Button size="small" onClick={() => handleOpenChunks(record)}>
                        查看 Chunk
                      </Button>
                    ),
                  },
                ]}
              />
            )}
          </Card>
        </Space>
      </div>

      <Modal
        title="Chunk 明细"
        open={chunksModalOpen}
        onCancel={() => setChunksModalOpen(false)}
        footer={null}
        width={900}
      >
        <List
          dataSource={chunks}
          renderItem={(chunk) => (
            <List.Item>
              <div className="w-full">
                <div className="flex items-center gap-2 mb-2">
                  <Tag>#{chunk.chunkIndex}</Tag>
                  {chunk.sectionTitle && <Tag color="green">{chunk.sectionTitle}</Tag>}
                  {chunk.pageNumber && <Tag color="purple">Page {chunk.pageNumber}</Tag>}
                  <span className="text-gray-400 text-xs">
                    offset {chunk.startOffset ?? '-'} - {chunk.endOffset ?? '-'}
                  </span>
                </div>
                <div className="bg-gray-50 rounded p-3 whitespace-pre-wrap text-sm text-gray-700">
                  {chunk.content}
                </div>
              </div>
            </List.Item>
          )}
        />
      </Modal>
    </div>
  );
};

export default KnowledgeBasePage;
