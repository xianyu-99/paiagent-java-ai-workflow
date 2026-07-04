import { useState, useEffect, useRef, useCallback } from 'react';
import { Button, Input, message, Checkbox, Select, Modal, List, Space, Tag } from 'antd';
import { SaveOutlined, FolderOpenOutlined, BugOutlined, LogoutOutlined, PlusOutlined, DatabaseOutlined, ShareAltOutlined, CopyOutlined, LinkOutlined, ApiOutlined, ExperimentOutlined } from '@ant-design/icons';
import { Edge, MarkerType, Node } from '@xyflow/react';
import NodePanel from '../components/NodePanel';
import FlowCanvas from '../components/FlowCanvas';
import DebugDrawer from '../components/DebugDrawer';
import LLMConfigModal from '../components/LLMConfigModal';
import { NodeConfigPanel } from '../components/node-config';
import { DraftSaver } from '../components/node-config/types';
import { logout } from '../api/auth';
import { useWorkflowStore } from '../store/workflowStore';
import { useAuthStore } from '../store/authStore';
import {
  createWorkflow,
  updateWorkflow,
  getWorkflows,
  getWorkflow,
  deleteWorkflow,
  getWorkflowPublish,
  publishWorkflow,
  unpublishWorkflow,
  createWorkflowTestCase,
  deleteWorkflowTestCase,
  listWorkflowTestCases,
  listWorkflowTestRuns,
  runWorkflowTestCases,
  Workflow,
  WorkflowPublish,
  WorkflowTestCase,
  WorkflowTestRun,
} from '../api/workflow';
import {
  listKnowledgeBases,
  KnowledgeBase,
} from '../api/knowledge';
import { getRefreshToken } from '../utils/auth';
import {
  bindDefaultEnterpriseKnowledgeBase,
  createDefaultWorkflowEdges,
  createDefaultWorkflowNodes,
  ENTERPRISE_SERVICE_DESK_KNOWLEDGE_BASE_NAME,
  normalizeWorkflowNodes,
  serializeWorkflowNodes,
} from '../utils/workflowNode';
import { useNavigate, useParams } from 'react-router-dom';

interface WorkflowCanvasData {
  nodes?: Node[];
  edges?: Edge[];
}

/**
 * 工作流编辑器页面
 */
const EditorPage = () => {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const { username, role, clearAuth } = useAuthStore();
  const { nodes, currentWorkflowId, setCurrentWorkflowId, selectedNode, setNodes, setEdges, setSelectedNode } = useWorkflowStore();
  const [workflowName, setWorkflowName] = useState('企业服务台助手');
  const [engineType, setEngineType] = useState('dag');
  const [saving, setSaving] = useState(false);
  const [debugDrawerOpen, setDebugDrawerOpen] = useState(false);
  const [loadModalOpen, setLoadModalOpen] = useState(false);
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loadingWorkflows, setLoadingWorkflows] = useState(false);
  const [publishModalOpen, setPublishModalOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishInfo, setPublishInfo] = useState<WorkflowPublish | null>(null);
  const [harnessModalOpen, setHarnessModalOpen] = useState(false);
  const [harnessLoading, setHarnessLoading] = useState(false);
  const [harnessRunning, setHarnessRunning] = useState(false);
  const [testCases, setTestCases] = useState<WorkflowTestCase[]>([]);
  const [testRuns, setTestRuns] = useState<WorkflowTestRun[]>([]);
  const [latestTestRun, setLatestTestRun] = useState<WorkflowTestRun | null>(null);
  const [newTestCase, setNewTestCase] = useState({
    name: '',
    inputData: '',
    expectedContains: '',
    expectedNotContains: '',
    expectedStatus: 'SUCCESS',
    requireCitation: false,
    requireAudio: false,
    maxDurationMs: '',
  });
  const hasLoadedRef = useRef<number | null>(null);
  const selectedNodeDraftSaverRef = useRef<DraftSaver | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  const registerSelectedNodeDraftSaver = useCallback((saver: DraftSaver | null) => {
    selectedNodeDraftSaverRef.current = saver;
  }, []);

  // 处理节点拖拽开始
  const handleDragStart = (event: React.DragEvent, nodeType: string, displayName: string) => {
    event.dataTransfer.setData('application/reactflow-type', nodeType);
    event.dataTransfer.setData('application/reactflow-label', displayName);
    event.dataTransfer.effectAllowed = 'move';
  };

  // 处理节点点击
  const handleNodeClick = useCallback((node: Node) => {
    if (selectedNode?.id === node.id) {
      return;
    }

    if (selectedNodeDraftSaverRef.current?.() === false) {
      return;
    }
    setSelectedNode(node);
  }, [selectedNode?.id, setSelectedNode]);

  const refreshKnowledgeBases = useCallback(async () => {
    try {
      const response = await listKnowledgeBases();
      if (response.code === 200) {
        setKnowledgeBases(response.data || []);
      }
    } catch (error) {
      console.error('加载知识库失败:', error);
    }
  }, []);

  useEffect(() => {
    refreshKnowledgeBases();
  }, [refreshKnowledgeBases]);

  useEffect(() => {
    if (id || currentWorkflowId) {
      return;
    }

    const enterpriseKnowledgeBaseId = knowledgeBases.find(
      (knowledgeBase) => knowledgeBase.name === ENTERPRISE_SERVICE_DESK_KNOWLEDGE_BASE_NAME
    )?.id;
    const boundNodes = bindDefaultEnterpriseKnowledgeBase(nodes, enterpriseKnowledgeBaseId);
    if (boundNodes !== nodes) {
      setNodes(boundNodes);
    }
  }, [currentWorkflowId, id, knowledgeBases, nodes, setNodes]);

  const refreshPublishStatus = useCallback(async (workflowId?: number | null) => {
    if (!workflowId) {
      setPublishInfo(null);
      return;
    }

    try {
      const result = await getWorkflowPublish(workflowId);
      if (result.code === 200) {
        setPublishInfo(result.data || null);
      }
    } catch (error) {
      console.error('加载发布状态失败:', error);
      setPublishInfo(null);
    }
  }, []);

  // 加载指定工作流
  const loadWorkflowById = useCallback(async (workflowId: number) => {
    try {
      const result = await getWorkflow(workflowId);
      if (result.code === 200) {
        const workflow = result.data;
        setWorkflowName(workflow.name);
        setEngineType(workflow.engineType || 'dag');
        setCurrentWorkflowId(workflow.id);
        
        const flowData = JSON.parse(workflow.flowData) as WorkflowCanvasData;
        
        // 加载节点
        const loadedNodes = normalizeWorkflowNodes(flowData.nodes || []);
        setNodes(loadedNodes);
        
        // 加载连线并恢复箭头
        const loadedEdges = (flowData.edges || []).map((edge) => ({
          ...edge,
          markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 20,
            height: 20,
          },
        }));
        setEdges(loadedEdges);
        setSelectedNode(null);
        selectedNodeDraftSaverRef.current = null;
        await refreshPublishStatus(workflow.id);

        message.success('工作流加载成功');
      }
    } catch {
      message.error('工作流加载失败');
    }
  }, [refreshPublishStatus, setCurrentWorkflowId, setEdges, setNodes, setSelectedNode]);

  // 从 URL 加载工作流
  useEffect(() => {
    if (id) {
      const workflowId = parseInt(id);
      // 避免重复加载 - 使用 ref 标记
      if (hasLoadedRef.current !== workflowId) {
        hasLoadedRef.current = workflowId;
        loadWorkflowById(workflowId);
      }
      return;
    }
    hasLoadedRef.current = null;
    setPublishInfo(null);
  }, [id, loadWorkflowById]);

  const persistWorkflowSnapshot = async (
    nodesSnapshot: Node[] = useWorkflowStore.getState().nodes,
    edgesSnapshot: Edge[] = useWorkflowStore.getState().edges,
    successMessage = '工作流保存成功',
    failureMessage = '保存失败'
  ): Promise<number | null> => {
    if (nodesSnapshot.length === 0) {
      message.warning('工作流为空,无法保存');
      return null;
    }

    const flowData = JSON.stringify({
      nodes: serializeWorkflowNodes(nodesSnapshot),
      edges: edgesSnapshot.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourceHandle: edge.sourceHandle,
        targetHandle: edge.targetHandle,
      })),
    });

    setSaving(true);
    try {
      const workflowId = useWorkflowStore.getState().currentWorkflowId;
      if (workflowId) {
        // 更新
        const result = await updateWorkflow(workflowId, {
          name: workflowName,
          flowData,
          engineType,
        });
        if (result.code !== 200) {
          message.error(result.message || failureMessage);
          return null;
        }
        message.success(successMessage);
        await refreshPublishStatus(workflowId);
        return workflowId;
      } else {
        // 创建
        const result = await createWorkflow({
          name: workflowName,
          description: '通过编辑器创建',
          flowData,
          engineType,
        });
        if (result.code === 200) {
          const workflowId = result.data.id;
          setCurrentWorkflowId(workflowId);
          // 更新 URL
          navigate(`/editor/${workflowId}`, { replace: true });
          setPublishInfo(null);
          message.success('工作流创建成功');
          return workflowId;
        }
        message.error(result.message || failureMessage);
      }
    } catch {
      message.error(failureMessage);
      return null;
    } finally {
      setSaving(false);
    }
    return null;
  };

  // 保存工作流
  const flushSelectedDraft = () => {
    return selectedNodeDraftSaverRef.current?.() !== false;
  };

  const handleSave = async (): Promise<number | null> => {
    if (!flushSelectedDraft()) {
      return null;
    }
    return persistWorkflowSnapshot(
      useWorkflowStore.getState().nodes,
      useWorkflowStore.getState().edges
    );
  };

  const persistNodeConfig = async () => {
    return persistWorkflowSnapshot(
      useWorkflowStore.getState().nodes,
      useWorkflowStore.getState().edges,
      '配置已保存到工作流',
      '配置已暂存到画布，但保存到后端失败'
    );
  };

  // 打开调试抽屉
  const handleOpenDebug = async () => {
    const workflowId = await handleSave();
    if (!workflowId) {
      message.warning('保存工作流失败，无法打开调试面板');
      return;
    }
    setDebugDrawerOpen(true);
  };

  const toAbsoluteUrl = (path?: string) => {
    if (!path) {
      return '';
    }
    if (/^https?:\/\//.test(path)) {
      return path;
    }
    return `${window.location.origin}${path.startsWith('/') ? path : `/${path}`}`;
  };

  const copyText = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
    } catch {
      message.error('复制失败');
    }
  };

  const handlePublishWorkflow = async () => {
    const workflowId = await handleSave();
    if (!workflowId) {
      return;
    }

    setPublishing(true);
    try {
      const result = await publishWorkflow(workflowId);
      if (result.code === 200) {
        setPublishInfo(result.data);
        setPublishModalOpen(true);
        message.success('工作流已发布');
      } else {
        message.error(result.message || '发布失败');
      }
    } catch {
      message.error('发布失败');
    } finally {
      setPublishing(false);
    }
  };

  const handleUnpublishWorkflow = async () => {
    if (!currentWorkflowId) {
      return;
    }

    setPublishing(true);
    try {
      const result = await unpublishWorkflow(currentWorkflowId);
      if (result.code === 200) {
        setPublishInfo(result.data || null);
        message.success('已取消发布');
      } else {
        message.error(result.message || '取消发布失败');
      }
    } catch {
      message.error('取消发布失败');
    } finally {
      setPublishing(false);
    }
  };

  const parseKeywordInput = (value: string) => value
    .split(/[\n,，]/)
    .map((item) => item.trim())
    .filter(Boolean);

  const resetNewTestCase = () => {
    setNewTestCase({
      name: '',
      inputData: '',
      expectedContains: '',
      expectedNotContains: '',
      expectedStatus: 'SUCCESS',
      requireCitation: false,
      requireAudio: false,
      maxDurationMs: '',
    });
  };

  const refreshHarnessData = useCallback(async (workflowId: number) => {
    setHarnessLoading(true);
    try {
      const [casesResult, runsResult] = await Promise.all([
        listWorkflowTestCases(workflowId),
        listWorkflowTestRuns(workflowId),
      ]);
      if (casesResult.code === 200) {
        setTestCases(casesResult.data || []);
      } else {
        message.error(casesResult.message || '测试用例加载失败');
      }
      if (runsResult.code === 200) {
        const runs = runsResult.data || [];
        setTestRuns(runs);
        setLatestTestRun(runs[0] || null);
      }
    } finally {
      setHarnessLoading(false);
    }
  }, []);

  const handleOpenHarness = async () => {
    const workflowId = await handleSave();
    if (!workflowId) {
      return;
    }
    setHarnessModalOpen(true);
    await refreshHarnessData(workflowId);
  };

  const handleCreateTestCase = async () => {
    if (!currentWorkflowId) {
      message.warning('请先保存工作流');
      return;
    }
    if (!newTestCase.name.trim() || !newTestCase.inputData.trim()) {
      message.warning('请填写测试名称和输入');
      return;
    }

    const result = await createWorkflowTestCase(currentWorkflowId, {
      name: newTestCase.name,
      inputData: newTestCase.inputData,
      expectedContains: parseKeywordInput(newTestCase.expectedContains),
      expectedNotContains: parseKeywordInput(newTestCase.expectedNotContains),
      expectedStatus: newTestCase.expectedStatus,
      requireCitation: newTestCase.requireCitation,
      requireAudio: newTestCase.requireAudio,
      maxDurationMs: newTestCase.maxDurationMs ? Number(newTestCase.maxDurationMs) : undefined,
      enabled: true,
    });
    if (result.code === 200) {
      message.success('测试用例已添加');
      resetNewTestCase();
      await refreshHarnessData(currentWorkflowId);
    } else {
      message.error(result.message || '测试用例添加失败');
    }
  };

  const handleDeleteTestCase = async (caseId: number) => {
    if (!currentWorkflowId) {
      return;
    }
    const result = await deleteWorkflowTestCase(currentWorkflowId, caseId);
    if (result.code === 200) {
      message.success('测试用例已删除');
      await refreshHarnessData(currentWorkflowId);
    } else {
      message.error(result.message || '测试用例删除失败');
    }
  };

  const handleRunHarness = async () => {
    if (!currentWorkflowId) {
      message.warning('请先保存工作流');
      return;
    }
    if (testCases.filter((item) => item.enabled).length === 0) {
      message.warning('请先添加测试用例');
      return;
    }

    setHarnessRunning(true);
    try {
      const result = await runWorkflowTestCases(currentWorkflowId);
      if (result.code === 200) {
        setLatestTestRun(result.data);
        setTestRuns((runs) => [result.data, ...runs.filter((run) => run.id !== result.data.id)].slice(0, 20));
        message.success(`测试完成：通过 ${result.data.passedCount}/${result.data.totalCount}`);
      } else {
        message.error(result.message || '测试运行失败');
      }
    } finally {
      setHarnessRunning(false);
    }
  };

  // 登出
  const handleLogout = async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await logout({ refreshToken });
      } catch {
        // 后端退出失败不阻塞本地登出
      }
    }
    clearAuth();
    navigate('/login');
  };

  // 获取可引用的节点列表（输出节点之前的所有节点）
  const getReferenceableNodes = () => {
    return nodes.filter(node => 
      node.id !== selectedNode?.id && node.data?.type !== 'output'
    );
  };

  // 获取节点的输出参数
  const getNodeOutputParams = (nodeType: string): string[] => {
    switch (nodeType) {
      case 'input':
        return ['user_input'];
      case 'llm':
      case 'agent':
      case 'openai':
      case 'deepseek':
      case 'qwen':
      case 'step':
      case 'zhipu':
      case 'ai_ping':
        return ['output', 'answer', 'citations', 'confidence', 'resolved', 'nextAction', 'ticketSummary', 'escalationReason', 'tokens'];
      case 'tts':
        return ['audioUrl', 'fileName', 'output'];
      case 'condition':
        return ['conditionResult', 'selectedBranch', 'output'];
      case 'rag':
        return ['output', 'context', 'citations', 'retrievedCount'];
      case 'media':
        return ['mediaUrl', 'mediaType', 'output'];
      case 'hyde':
        return ['hydeQuery', 'originalQuery', 'output'];
      case 'query_expansion':
        return ['expandedQueries', 'originalQuery', 'output'];
      default:
        return ['output'];
    }
  };

  // 获取所有可引用的参数（节点.参数名格式）
  const getReferenceableParams = () => {
    const params: { label: string; value: string }[] = [];
    getReferenceableNodes().forEach(node => {
      const nodeType = (node.data?.type as string) || '';
      const nodeLabel = (node.data?.label as string) || node.id;
      const configuredOutputParams = Array.isArray(node.data?.outputParams)
        ? node.data.outputParams
            .map((param) => typeof param === 'object' && param !== null ? String((param as { name?: unknown }).name || '') : '')
            .filter(Boolean)
        : [];
      const outputParams = Array.from(new Set([
        ...configuredOutputParams,
        ...getNodeOutputParams(nodeType),
      ]));
      
      outputParams.forEach(param => {
        params.push({
          label: `${nodeLabel}.${param}`,
          value: `${node.id}.${param}`
        });
      });
    });
    return params;
  };

  // 打开加载工作流对话框
  const fetchWorkflowList = async () => {
    const result = await getWorkflows();
    if (result.code === 200) {
      setWorkflows(result.data);
    }
  };

  const handleOpenLoadModal = async () => {
    setLoadingWorkflows(true);
    setLoadModalOpen(true);
    try {
      await fetchWorkflowList();
    } catch {
      message.error('获取工作流列表失败');
    } finally {
      setLoadingWorkflows(false);
    }
  };

  // 加载选中的工作流
  const handleLoadWorkflow = async (workflow: Workflow) => {
    if (!flushSelectedDraft()) {
      return;
    }
    setLoadModalOpen(false);
    hasLoadedRef.current = null;

    if (currentWorkflowId === workflow.id) {
      await loadWorkflowById(workflow.id);
      return;
    }

    navigate(`/editor/${workflow.id}`);
  };

  const handleDeleteWorkflow = (workflow: Workflow) => {
    Modal.confirm({
      title: '删除工作流',
      content: `确定删除「${workflow.name}」吗？此操作不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteWorkflow(workflow.id);

          if (currentWorkflowId === workflow.id) {
            setCurrentWorkflowId(null);
            setSelectedNode(null);
            selectedNodeDraftSaverRef.current = null;
            navigate('/editor', { replace: true });
          }

          await fetchWorkflowList();
          message.success('工作流删除成功');
        } catch {
          message.error('删除工作流失败');
        }
      },
    });
  };

  // 新建工作流
  const handleCreateNew = () => {
    if (!flushSelectedDraft()) {
      return;
    }
    hasLoadedRef.current = null;
    setCurrentWorkflowId(null);
    setSelectedNode(null);
    selectedNodeDraftSaverRef.current = null;
    setWorkflowName('企业服务台助手');
    const enterpriseKnowledgeBaseId = knowledgeBases.find(
      (knowledgeBase) => knowledgeBase.name === ENTERPRISE_SERVICE_DESK_KNOWLEDGE_BASE_NAME
    )?.id;
    
    // 创建企业服务台默认模板
    setNodes(createDefaultWorkflowNodes(enterpriseKnowledgeBaseId));
    setEdges(createDefaultWorkflowEdges());
    navigate('/editor');
    message.info('已创建新工作流');
  };

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* 顶部工具栏 */}
      <div className="bg-white shadow-sm px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-nowrap items-center gap-3">
          <h1 className="text-2xl font-bold text-gray-800 flex-none">PaiAgent</h1>
          <Input
            value={workflowName}
            onChange={(e) => setWorkflowName(e.target.value)}
            className="flex-none"
            placeholder="工作流名称"
            variant="borderless"
            style={{ width: 'min(240px, 42vw)', borderBottom: '2px solid #e5e7eb' }}
          />
          <Select
            value={engineType}
            onChange={(value) => setEngineType(value)}
            className="w-36"
            options={[
              { value: 'dag', label: 'DAG 引擎' },
              { value: 'langgraph', label: 'LangGraph 引擎' }
            ]}
          />
        </div>
        
        <div className="flex flex-wrap items-center justify-end gap-2">
          {role === 'ADMIN' && <LLMConfigModal />}
          <Button
            icon={<DatabaseOutlined />}
            onClick={() => navigate('/knowledge')}
          >
            知识库
          </Button>
          <Button
            icon={<PlusOutlined />}
            onClick={handleCreateNew}
          >
            新建
          </Button>
          <Button
            icon={<FolderOpenOutlined />}
            onClick={handleOpenLoadModal}
          >
            加载
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            onClick={handleSave}
            loading={saving}
          >
            保存
          </Button>
          <Button
            icon={<ShareAltOutlined />}
            onClick={handlePublishWorkflow}
            loading={publishing}
          >
            {publishInfo?.enabled ? '已发布' : '发布'}
          </Button>
          <Button
            icon={<ExperimentOutlined />}
            onClick={handleOpenHarness}
            loading={harnessLoading}
          >
            测试集
          </Button>
          <Button
            type="primary"
            icon={<BugOutlined />}
            onClick={handleOpenDebug}
          >
            调试
          </Button>
          <div className="flex min-w-0 items-center gap-2 px-3 py-1 bg-gray-50 rounded-lg">
            <span className="max-w-44 truncate text-gray-600" title={`${username}${role ? ` / ${role}` : ''}`}>
              👤 {username}{role ? ` / ${role}` : ''}
            </span>
            <Button
              icon={<LogoutOutlined />}
              onClick={handleLogout}
              type="text"
            >
              登出
            </Button>
          </div>
        </div>
      </div>

      {/* 主要内容区域 */}
      <div className="flex-1 flex overflow-hidden gap-4 p-4">
        {/* 左侧节点面板 */}
        <div className="w-64 flex-shrink-0 bg-white rounded-lg shadow-sm overflow-hidden">
          <NodePanel onDragStart={handleDragStart} />
        </div>

        {/* 中间画布 */}
        <div className="flex-1 bg-white rounded-lg shadow-sm overflow-hidden">
          <FlowCanvas onNodeClick={handleNodeClick} />
        </div>

        {/* 右侧配置面板 */}
        <div className="w-[420px] flex-shrink-0 bg-white rounded-lg shadow-sm overflow-y-auto p-4">
          <h3 className="text-lg font-semibold mb-4 text-gray-800">节点配置</h3>
          {selectedNode ? (
            <div>
              <div className="mb-4 p-3 bg-gray-50 rounded-lg">
                <p className="text-sm text-gray-500 mb-1">节点 ID</p>
                <p className="text-gray-700 font-medium">{selectedNode.id}</p>
              </div>
              <div className="mb-4 p-3 bg-gray-50 rounded-lg">
                <p className="text-sm text-gray-500 mb-1">节点类型</p>
                <p className="text-gray-700 font-medium">{String(selectedNode.data?.type || '')}</p>
              </div>

              <NodeConfigPanel
                node={selectedNode}
                onSave={async () => { await persistNodeConfig(); }}
                getReferenceableParams={getReferenceableParams}
                key={`${currentWorkflowId ?? 'draft'}-${selectedNode.id}`}
                registerDraftSaver={registerSelectedNodeDraftSaver}
              />
            </div>
          ) : (
            <div className="text-center py-12">
              <p className="text-gray-400 text-sm">请选择一个节点</p>
            </div>
          )}
        </div>
      </div>

      {/* 调试抽屉 */}
      <DebugDrawer
        open={debugDrawerOpen}
        onClose={() => setDebugDrawerOpen(false)}
      />

      <Modal
        title="发布工作流"
        open={publishModalOpen}
        onCancel={() => setPublishModalOpen(false)}
        footer={publishInfo?.enabled ? [
          <Button key="unpublish" danger loading={publishing} onClick={handleUnpublishWorkflow}>
            取消发布
          </Button>,
          <Button key="close" onClick={() => setPublishModalOpen(false)}>
            关闭
          </Button>
        ] : [
          <Button key="close" onClick={() => setPublishModalOpen(false)}>
            关闭
          </Button>
        ]}
        width={720}
      >
        {publishInfo?.enabled ? (
          <Space direction="vertical" className="w-full" size="middle">
            <div>
              <div className="text-sm text-gray-500 mb-2">公开页面</div>
              <Space.Compact className="w-full">
                <Button icon={<LinkOutlined />} />
                <Input
                  readOnly
                  value={toAbsoluteUrl(publishInfo.publicPagePath)}
                />
                <Button icon={<CopyOutlined />} onClick={() => copyText(toAbsoluteUrl(publishInfo.publicPagePath))}>
                  复制
                </Button>
              </Space.Compact>
            </div>
            <div>
              <div className="text-sm text-gray-500 mb-2">API POST 调用地址</div>
              <Space.Compact className="w-full">
                <Button icon={<ApiOutlined />} />
                <Input
                  readOnly
                  value={toAbsoluteUrl(publishInfo.publicApiPath)}
                />
                <Button icon={<CopyOutlined />} onClick={() => copyText(toAbsoluteUrl(publishInfo.publicApiPath))}>
                  复制
                </Button>
              </Space.Compact>
              <div className="text-sm text-gray-500 mt-3 mb-2">API 访问密钥</div>
              <Space.Compact className="w-full">
                <Input.Password
                  readOnly
                  value={publishInfo.apiAccessKey || ''}
                />
                <Button icon={<CopyOutlined />} onClick={() => copyText(publishInfo.apiAccessKey || '')}>
                  复制
                </Button>
              </Space.Compact>
              <div className="text-xs text-gray-500 mt-2">
                这是给程序调用的接口，浏览器地址栏不能直接打开。请求头需带 X-PaiAgent-Api-Key，POST JSON: {`{ "inputData": "你的输入" }`}
              </div>
              <pre className="mt-2 rounded border bg-gray-50 p-3 text-xs overflow-auto">
{`curl -X POST "${toAbsoluteUrl(publishInfo.publicApiPath)}" \\
  -H "Content-Type: application/json" \\
  -H "X-PaiAgent-Api-Key: ${publishInfo.apiAccessKey || '<API_KEY>'}" \\
  -d "{\\"inputData\\":\\"你的输入\\"}"`}
              </pre>
            </div>
            <Button type="primary" onClick={() => window.open(toAbsoluteUrl(publishInfo.publicPagePath), '_blank')}>
              打开公开页面
            </Button>
          </Space>
        ) : (
          <div className="text-gray-500">当前工作流尚未发布。</div>
        )}
      </Modal>

      <Modal
        title="Workflow Test Harness"
        open={harnessModalOpen}
        onCancel={() => setHarnessModalOpen(false)}
        footer={[
          <Button key="refresh" onClick={() => currentWorkflowId && refreshHarnessData(currentWorkflowId)} loading={harnessLoading}>
            刷新
          </Button>,
          <Button key="run" type="primary" icon={<ExperimentOutlined />} onClick={handleRunHarness} loading={harnessRunning}>
            运行测试集
          </Button>,
          <Button key="close" onClick={() => setHarnessModalOpen(false)}>
            关闭
          </Button>
        ]}
        width={960}
      >
        <Space direction="vertical" className="w-full" size="large">
          <div className="rounded border bg-gray-50 p-4">
            <div className="font-medium mb-3">新增测试用例</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <Input
                placeholder="用例名称，例如 RAG 引用校验"
                value={newTestCase.name}
                onChange={(e) => setNewTestCase({ ...newTestCase, name: e.target.value })}
              />
              <Select
                value={newTestCase.expectedStatus}
                onChange={(value) => setNewTestCase({ ...newTestCase, expectedStatus: value })}
                options={[
                  { label: '期望成功', value: 'SUCCESS' },
                  { label: '期望失败', value: 'FAILED' }
                ]}
              />
            </div>
            <Input.TextArea
              className="mt-3"
              rows={3}
              placeholder="测试输入"
              value={newTestCase.inputData}
              onChange={(e) => setNewTestCase({ ...newTestCase, inputData: e.target.value })}
            />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-3">
              <Input.TextArea
                rows={2}
                placeholder="期望包含关键词，逗号或换行分隔"
                value={newTestCase.expectedContains}
                onChange={(e) => setNewTestCase({ ...newTestCase, expectedContains: e.target.value })}
              />
              <Input.TextArea
                rows={2}
                placeholder="不应包含关键词，逗号或换行分隔"
                value={newTestCase.expectedNotContains}
                onChange={(e) => setNewTestCase({ ...newTestCase, expectedNotContains: e.target.value })}
              />
            </div>
            <div className="flex flex-wrap items-center gap-4 mt-3">
              <Checkbox
                checked={newTestCase.requireCitation}
                onChange={(e) => setNewTestCase({ ...newTestCase, requireCitation: e.target.checked })}
              >
                要求 RAG 引用
              </Checkbox>
              <Checkbox
                checked={newTestCase.requireAudio}
                onChange={(e) => setNewTestCase({ ...newTestCase, requireAudio: e.target.checked })}
              >
                要求 TTS 音频
              </Checkbox>
              <Input
                className="w-44"
                type="number"
                placeholder="最大耗时 ms"
                value={newTestCase.maxDurationMs}
                onChange={(e) => setNewTestCase({ ...newTestCase, maxDurationMs: e.target.value })}
              />
              <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateTestCase}>
                添加用例
              </Button>
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="font-medium">测试用例</div>
              <span className="text-sm text-gray-500">{testCases.length} 条</span>
            </div>
            <List
              bordered
              loading={harnessLoading}
              dataSource={testCases}
              locale={{ emptyText: '暂无测试用例' }}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button key="delete" danger type="link" onClick={() => handleDeleteTestCase(item.id)}>
                      删除
                    </Button>
                  ]}
                >
                  <List.Item.Meta
                    title={
                      <Space>
                        <span>{item.name}</span>
                        <Tag color={item.enabled ? 'green' : 'default'}>{item.enabled ? '启用' : '停用'}</Tag>
                        {item.requireCitation && <Tag color="blue">引用</Tag>}
                        {item.requireAudio && <Tag color="purple">音频</Tag>}
                      </Space>
                    }
                    description={
                      <div className="text-sm">
                        <div className="text-gray-500 line-clamp-2">{item.inputData}</div>
                        {item.expectedContains?.length > 0 && (
                          <div className="mt-1">包含：{item.expectedContains.join('、')}</div>
                        )}
                      </div>
                    }
                  />
                </List.Item>
              )}
            />
          </div>

          {latestTestRun && (
            <div>
              <div className="flex items-center justify-between mb-2">
                <div className="font-medium">最近运行结果</div>
                <Space>
                  <Tag color={latestTestRun.status === 'PASSED' ? 'green' : 'red'}>{latestTestRun.status}</Tag>
                  <span className="text-sm text-gray-500">
                    通过 {latestTestRun.passedCount}/{latestTestRun.totalCount}，耗时 {latestTestRun.duration}ms
                  </span>
                </Space>
              </div>
              <List
                bordered
                dataSource={latestTestRun.results || []}
                locale={{ emptyText: '暂无运行详情' }}
                renderItem={(item) => (
                  <List.Item>
                    <List.Item.Meta
                      title={
                        <Space>
                          <span>{item.caseName}</span>
                          <Tag color={item.status === 'PASSED' ? 'green' : 'red'}>{item.status}</Tag>
                          {item.duration !== undefined && <span className="text-gray-500 text-sm">{item.duration}ms</span>}
                        </Space>
                      }
                      description={
                        <div className="space-y-1">
                          {(item.assertionResults || []).map((assertion, index) => (
                            <div key={`${assertion.type}-${index}`} className={assertion.passed ? 'text-green-600' : 'text-red-600'}>
                              {assertion.passed ? '通过' : '失败'} · {assertion.message}
                            </div>
                          ))}
                          {item.errorMessage && <div className="text-red-600">{item.errorMessage}</div>}
                        </div>
                      }
                    />
                  </List.Item>
                )}
              />
            </div>
          )}

          {testRuns.length > 0 && (
            <div>
              <div className="font-medium mb-2">历史运行</div>
              <Space wrap>
                {testRuns.slice(0, 8).map((run) => (
                  <Tag key={run.id} color={run.status === 'PASSED' ? 'green' : 'red'}>
                    #{run.id} {run.passedCount}/{run.totalCount} · {run.duration}ms
                  </Tag>
                ))}
              </Space>
            </div>
          )}
        </Space>
      </Modal>

      {/* 加载工作流对话框 */}
      <Modal
        title="加载工作流"
        open={loadModalOpen}
        onCancel={() => setLoadModalOpen(false)}
        footer={null}
        width={600}
      >
        <List
          loading={loadingWorkflows}
          dataSource={workflows}
          renderItem={(workflow) => (
            <List.Item
              key={workflow.id}
              actions={[
                <Button key="load" type="link" onClick={() => handleLoadWorkflow(workflow)}>
                  加载
                </Button>,
                <Button
                  key="delete"
                  type="link"
                  danger
                  onClick={() => handleDeleteWorkflow(workflow)}
                >
                  删除
                </Button>
              ]}
            >
              <List.Item.Meta
                title={workflow.name}
                description={`创建于: ${new Date(workflow.createdAt).toLocaleString()}`}
              />
            </List.Item>
          )}
        />
      </Modal>
    </div>
  );
};

export default EditorPage;
