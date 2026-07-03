import { useState, useEffect, useRef, useCallback } from 'react';
import { Button, Input, Form, message, Checkbox, Select, Modal, List, Tabs, Upload, Space, Tag } from 'antd';
import { SaveOutlined, FolderOpenOutlined, BugOutlined, LogoutOutlined, PlusOutlined, DeleteOutlined, UploadOutlined, DatabaseOutlined, ShareAltOutlined, CopyOutlined, LinkOutlined, ApiOutlined, ExperimentOutlined } from '@ant-design/icons';
import { Edge, MarkerType, Node } from '@xyflow/react';
import NodePanel from '../components/NodePanel';
import FlowCanvas from '../components/FlowCanvas';
import DebugDrawer from '../components/DebugDrawer';
import SkillSelector from '../components/SkillSelector';
import LLMConfigModal from '../components/LLMConfigModal';
import { NodeConfigPanel } from '../components/node-config';
import { logout } from '../api/auth';
import { useWorkflowStore } from '../store/workflowStore';
import { useAuthStore } from '../store/authStore';
import { useLLMConfigStore } from '../store/llmConfigStore';
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
  createKnowledgeBase,
  listKnowledgeBases,
  rebuildKnowledgeBaseEmbeddings,
  uploadKnowledgeDocument,
  uploadKnowledgeFile,
  KnowledgeBase,
} from '../api/knowledge';
import { getRefreshToken } from '../utils/auth';
import {
  getProviderDefaultBaseUrl,
  getProviderFromNodeType,
  getProviderLabel,
  getProviderModelPlaceholder,
  getSupportedProviderOptions,
  isLlmNodeType,
  normalizeProviderKey,
  SUPPORTED_LLM_PROVIDERS,
} from '../utils/provider';
import {
  bindDefaultEnterpriseKnowledgeBase,
  createDefaultWorkflowEdges,
  createDefaultWorkflowNodes,
  ENTERPRISE_SERVICE_DESK_KNOWLEDGE_BASE_NAME,
  normalizeWorkflowNodes,
  serializeWorkflowNodes,
} from '../utils/workflowNode';
import { useNavigate, useParams } from 'react-router-dom';

const DEFAULT_PROVIDER_MODELS: Record<string, string> = {
  openai: 'gpt-4o-mini',
  deepseek: 'deepseek-chat',
  qwen: 'qwen-plus',
  moonshot: 'kimi-k2.6',
  kimi_code: 'kimi-for-coding',
  mimo: 'mimo-v2.5-pro',
};

interface OutputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface LlmInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface LlmOutputParam {
  name: string;
  type: string;
  description?: string;
}

interface TtsInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface TtsOutputParam {
  name: string;
  value: string;
}

interface ConditionConfig {
  leftType: 'input' | 'reference';
  leftValue: string;
  leftReference?: string;
  operator: string;
  rightValue: string;
  caseSensitive: boolean;
}

interface RagConfig {
  knowledgeBaseId?: number;
  configId?: number;
  retrievalOnly: boolean;
  questionReference?: string;
  topK: number;
  minScore: number;
  contextWindow: number;
  contextMaxChars: number;
  prompt: string;
}

interface WorkflowCanvasData {
  nodes?: Node[];
  edges?: Edge[];
}

const MAX_KNOWLEDGE_UPLOAD_SIZE_MB = 50;
const MAX_KNOWLEDGE_UPLOAD_SIZE = MAX_KNOWLEDGE_UPLOAD_SIZE_MB * 1024 * 1024;

/**
 * 工作流编辑器页面
 */
const EditorPage = () => {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const { username, role, clearAuth } = useAuthStore();
  const { nodes, edges, currentWorkflowId, setCurrentWorkflowId, selectedNode, setNodes, setEdges } = useWorkflowStore();
  const [workflowName, setWorkflowName] = useState('企业服务台助手');
  const [engineType, setEngineType] = useState('dag');
  const [saving, setSaving] = useState(false);
  const [debugDrawerOpen, setDebugDrawerOpen] = useState(false);
  const [outputParams, setOutputParams] = useState<OutputParam[]>([]);
  const [responseContent, setResponseContent] = useState('');
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
  const selectedNodeDraftSaverRef = useRef<(() => void) | null>(null);
  
  // LLM 节点配置状态
  const [llmConfig, setLlmConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    temperature: 0.7,
    prompt: '',
    skillName: ''
  });
  const [llmInputParams, setLlmInputParams] = useState<LlmInputParam[]>([]);
  const [llmOutputParams, setLlmOutputParams] = useState<LlmOutputParam[]>([]);

  // LLM 全局配置 Store
  const { configs: llmGlobalConfigs, fetchAllConfigs: fetchLLMGlobalConfigs } = useLLMConfigStore();
  const providerOptions = getSupportedProviderOptions();

  // TTS 节点配置状态
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
  const [conditionConfig, setConditionConfig] = useState<ConditionConfig>({
    leftType: 'reference',
    leftValue: '',
    leftReference: 'input-default.input',
    operator: 'contains',
    rightValue: '',
    caseSensitive: false
  });
  const [ragConfig, setRagConfig] = useState<RagConfig>({
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
  const [knowledgeFileName, setKnowledgeFileName] = useState('manual.txt');
  const [knowledgeContent, setKnowledgeContent] = useState('');
  const [knowledgeReindexing, setKnowledgeReindexing] = useState(false);
  const [knowledgeLocalFile, setKnowledgeLocalFile] = useState<File | null>(null);
  const [knowledgeFileUploading, setKnowledgeFileUploading] = useState(false);

  const resolveSelectedNodeProvider = (node: Node | null) => {
    if (!node) {
      return '';
    }

    const configuredProvider = normalizeProviderKey(String(node.data?.provider || ''));
    if (configuredProvider) {
      return configuredProvider;
    }

    return getProviderFromNodeType(String(node.data?.type || ''));
  };

  const shouldReplaceProviderDefault = (
    currentValue: string,
    previousProvider: string,
    defaultResolver: (provider: string) => string
  ) => {
    if (!currentValue) {
      return true;
    }

    if (Boolean(previousProvider) && currentValue === defaultResolver(previousProvider)) {
      return true;
    }

    return SUPPORTED_LLM_PROVIDERS.some((provider) => currentValue === defaultResolver(provider));
  };

  // 处理节点拖拽开始
  const handleDragStart = (event: React.DragEvent, nodeType: string, displayName: string) => {
    event.dataTransfer.setData('application/reactflow-type', nodeType);
    event.dataTransfer.setData('application/reactflow-label', displayName);
    event.dataTransfer.effectAllowed = 'move';
  };

  // 处理节点点击
  const handleNodeClick = (node: Node) => {
    console.log('Node clicked:', node);

    useWorkflowStore.getState().setSelectedNode(node);

    // 加载节点配置
    if (node.data?.type === 'output') {
      setOutputParams((node.data?.outputParams as OutputParam[]) || []);
      setResponseContent((node.data?.responseContent as string) || '');
    } else if (isLlmNodeType(String(node.data?.type || ''))) {
      // 加载 LLM 节点配置
      const configId = (node.data?.configId as number) || undefined;
      const matchedGlobalConfig = configId
        ? llmGlobalConfigs.find(c => c.id === configId)
        : undefined;
      const provider = normalizeProviderKey(
        matchedGlobalConfig?.provider ||
        String(node.data?.provider || '') ||
        getProviderFromNodeType(String(node.data?.type || ''))
      );
      setLlmConfig({
        provider,
        configId,
        apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
        apiKey: configId ? '' : (node.data?.apiKey as string) || '',
        model: matchedGlobalConfig?.model || (node.data?.model as string) || '',
        temperature: matchedGlobalConfig?.temperature || (node.data?.temperature as number) || 0.7,
        prompt: (node.data?.prompt as string) || '',
        skillName: (node.data?.skillName as string) || ''
      });
      setLlmInputParams((node.data?.inputParams as LlmInputParam[]) || []);
      setLlmOutputParams((node.data?.outputParams as LlmOutputParam[]) || []);
    } else if (node.data?.type === 'tts') {
      // 加载 TTS 节点配置
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
    } else if (node.data?.type === 'condition') {
      setConditionConfig({
        leftType: (node.data?.leftType as 'input' | 'reference') || 'reference',
        leftValue: (node.data?.leftValue as string) || '',
        leftReference: (node.data?.leftReference as string) || 'input-default.input',
        operator: (node.data?.operator as string) || 'contains',
        rightValue: (node.data?.rightValue as string) || '',
        caseSensitive: Boolean(node.data?.caseSensitive)
      });
    } else if (node.data?.type === 'rag') {
      const inputParams = (node.data?.inputParams as LlmInputParam[]) || [];
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
    }
  };

  // 初始化加载 LLM 全局配置
  useEffect(() => {
    fetchLLMGlobalConfigs();
  }, [fetchLLMGlobalConfigs]);

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

  // 当全局配置异步加载完成后，补齐当前选中节点的展示配置
  useEffect(() => {
    if (!selectedNode) return;
    const nodeType = selectedNode.data?.type;
    if (!isLlmNodeType(String(nodeType || ''))) return;
    if (!llmConfig.configId) return;

    const config = llmGlobalConfigs.find(c => c.id === llmConfig.configId);
    if (!config) return;

    const needsSync =
      llmConfig.apiUrl !== config.apiUrl ||
      llmConfig.model !== config.model ||
      llmConfig.temperature !== config.temperature ||
      llmConfig.apiKey !== '';

    if (needsSync) {
      setLlmConfig(prev => ({
        ...prev,
        provider: normalizeProviderKey(config.provider),
        apiUrl: config.apiUrl,
        apiKey: '',
        model: config.model,
        temperature: config.temperature
      }));
    }
  }, [llmGlobalConfigs, selectedNode, llmConfig]);

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
        console.log('加载的工作流数据:', flowData);
        
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
        await refreshPublishStatus(workflow.id);
        
        // 恢复输出节点配置
        const outputNode = loadedNodes.find((node) => node.data?.type === 'output');
        console.log('找到输出节点:', outputNode);
        console.log('输出节点配置 - outputParams:', outputNode?.data?.outputParams);
        console.log('输出节点配置 - responseContent:', outputNode?.data?.responseContent);

        const rawOutputParams = outputNode?.data?.outputParams;
        const rawResponseContent = outputNode?.data?.responseContent;
        setOutputParams(Array.isArray(rawOutputParams) ? rawOutputParams as OutputParam[] : []);
        setResponseContent(typeof rawResponseContent === 'string' ? rawResponseContent : '');
        
        message.success('工作流加载成功');
      }
    } catch {
      message.error('工作流加载失败');
    }
  }, [refreshPublishStatus, setCurrentWorkflowId, setEdges, setNodes]);

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
  const handleSave = async (): Promise<number | null> => {
    selectedNodeDraftSaverRef.current?.();
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
  const handleOpenDebug = () => {
    if (!currentWorkflowId) {
      message.warning('请先保存工作流');
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

  // 添加输出参数
  const handleAddOutputParam = () => {
    setOutputParams([...outputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除输出参数
  const handleRemoveOutputParam = (index: number) => {
    setOutputParams(outputParams.filter((_, i) => i !== index));
  };

  // 更新输出参数
  const handleUpdateOutputParam = (index: number, field: keyof OutputParam, value: string) => {
    const newParams = [...outputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setOutputParams(newParams);
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
      const outputParams = getNodeOutputParams(nodeType);
      
      outputParams.forEach(param => {
        params.push({
          label: `${nodeLabel}.${param}`,
          value: `${node.id}.${param}`
        });
      });
    });
    return params;
  };

  // 保存输出节点配置
  const handleSaveOutputConfig = async () => {
    if (!selectedNode) return;

    // 验证参数配置
    for (const param of outputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证回答内容配置中的参数引用
    const paramNames = new Set(outputParams.map(p => p.name));
    const templateParamRegex = /\{\{(\w+)\}\}/g;
    const matches = responseContent.matchAll(templateParamRegex);
    const undefinedParams: string[] = [];
    
    for (const match of matches) {
      const paramName = match[1];
      if (!paramNames.has(paramName)) {
        undefinedParams.push(paramName);
      }
    }
    
    if (undefinedParams.length > 0) {
      message.warning(`回答内容中引用了未定义的参数: ${undefinedParams.join(', ')}`);
      return;
    }

    // 保存到节点的 data 中
    const updatedData = {
      ...selectedNode.data,
      outputParams,
      responseContent
    };

    console.log('保存输出节点配置:', {
      nodeId: selectedNode.id,
      outputParams,
      responseContent,
      updatedData
    });

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    await persistNodeConfig();
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
    hasLoadedRef.current = null;
    setCurrentWorkflowId(null);
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

  // 保存 LLM 节点配置
  const handleSaveLlmConfig = async () => {
    if (!selectedNode) return;

    // 验证输入参数
    for (const param of llmInputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证提示词
    if (!llmConfig.prompt) {
      message.warning('请填写提示词模板');
      return;
    }

    // 验证提示词中的参数引用
    const paramNames = new Set(llmInputParams.map(p => p.name));
    const templateParamRegex = /\{\{(\w+)\}\}/g;
    const matches = llmConfig.prompt.matchAll(templateParamRegex);
    const undefinedParams: string[] = [];

    for (const match of matches) {
      const paramName = match[1];
      if (!paramNames.has(paramName)) {
        undefinedParams.push(paramName);
      }
    }

    if (undefinedParams.length > 0) {
      message.warning(`提示词模板中引用了未定义的参数: ${undefinedParams.join(', ')}`);
      return;
    }

    // 如果没有选择全局配置，需要验证 API 配置
    if (!llmConfig.configId) {
      if (!llmConfig.provider) {
        message.warning('请选择供应商');
        return;
      }
      if (!llmConfig.apiUrl) {
        message.warning('请选择全局配置或填写 API 地址');
        return;
      }
      if (!llmConfig.apiKey) {
        message.warning('请选择全局配置或填写 API 密钥');
        return;
      }
      if (!llmConfig.model) {
        message.warning('请选择全局配置或填写模型名称');
        return;
      }
    }

    const useGlobalConfig = !!llmConfig.configId;
    const updatedData = {
      ...selectedNode.data,
      provider: llmConfig.provider,
      configId: llmConfig.configId,
      apiUrl: useGlobalConfig ? '' : llmConfig.apiUrl,
      apiKey: useGlobalConfig ? '' : llmConfig.apiKey,
      model: useGlobalConfig ? '' : llmConfig.model,
      temperature: useGlobalConfig ? 0.7 : llmConfig.temperature,
      prompt: llmConfig.prompt,
      skillName: llmConfig.skillName,
      inputParams: llmInputParams,
      outputParams: llmOutputParams
    };

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    await persistNodeConfig();
  };

  // 添加 LLM 输入参数
  const handleAddLlmInputParam = () => {
    setLlmInputParams([...llmInputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除 LLM 输入参数
  const handleRemoveLlmInputParam = (index: number) => {
    setLlmInputParams(llmInputParams.filter((_, i) => i !== index));
  };

  // 更新 LLM 输入参数
  const handleUpdateLlmInputParam = (index: number, field: keyof LlmInputParam, value: string) => {
    const newParams = [...llmInputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmInputParams(newParams);
  };

  // 保存 TTS 节点配置
  const handleSaveTtsConfig = async () => {
    if (!selectedNode) return;

    if (!ttsConfig.apiKey && !ttsConfig.apiKeyConfigured) {
      message.warning('请填写 API Key');
      return;
    }
    if (!ttsConfig.model) {
      message.warning('请填写模型名称');
      return;
    }

    // 验证输入参数
    for (const param of ttsInputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证输出参数
    for (const param of ttsOutputParams) {
      if (!param.name) {
        message.warning('请填写所有输出参数名');
        return;
      }
    }

    const updatedData = {
      ...selectedNode.data,
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

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    await persistNodeConfig();
  };

  // 添加 TTS 输入参数
  const handleAddTtsInputParam = () => {
    setTtsInputParams([...ttsInputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除 TTS 输入参数
  const handleRemoveTtsInputParam = (index: number) => {
    setTtsInputParams(ttsInputParams.filter((_, i) => i !== index));
  };

  // 更新 TTS 输入参数
  const handleUpdateTtsInputParam = (index: number, field: keyof TtsInputParam, value: string) => {
    const newParams = [...ttsInputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setTtsInputParams(newParams);
  };

  // 添加 TTS 输出参数
  const handleAddTtsOutputParam = () => {
    setTtsOutputParams([...ttsOutputParams, { name: '', value: '' }]);
  };

  // 删除 TTS 输出参数
  const handleRemoveTtsOutputParam = (index: number) => {
    setTtsOutputParams(ttsOutputParams.filter((_, i) => i !== index));
  };

  // 更新 TTS 输出参数
  const handleUpdateTtsOutputParam = (index: number, field: keyof TtsOutputParam, value: string) => {
    const newParams = [...ttsOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setTtsOutputParams(newParams);
  };

  const handleSaveConditionConfig = async () => {
    if (!selectedNode) return;

    if (conditionConfig.leftType === 'reference' && !conditionConfig.leftReference) {
      message.warning('请选择左侧引用参数');
      return;
    }
    if (conditionConfig.leftType === 'input' && !conditionConfig.leftValue) {
      message.warning('请填写左侧固定值');
      return;
    }
    if (!['empty', 'not_empty'].includes(conditionConfig.operator) && !conditionConfig.rightValue) {
      message.warning('请填写右侧比较值');
      return;
    }

    useWorkflowStore.getState().updateNode(selectedNode.id, {
      ...selectedNode.data,
      ...conditionConfig,
    });
    await persistNodeConfig();
  };

  const handleSaveRagConfig = async () => {
    if (!selectedNode) return;
    if (!ragConfig.knowledgeBaseId) {
      message.warning('请选择知识库');
      return;
    }
    if (!ragConfig.retrievalOnly && !ragConfig.configId) {
      message.warning('请选择用于回答的全局 LLM 配置');
      return;
    }
    if (!ragConfig.questionReference) {
      message.warning('请选择问题来源');
      return;
    }

    useWorkflowStore.getState().updateNode(selectedNode.id, {
      ...selectedNode.data,
      knowledgeBaseId: ragConfig.knowledgeBaseId,
      configId: ragConfig.configId,
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
    });
    await persistNodeConfig();
  };

  const handleCreateKnowledgeBase = async () => {
    if (!newKnowledgeBaseName.trim()) {
      message.warning('请填写知识库名称');
      return;
    }
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
    if (!ragConfig.knowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    if (!knowledgeContent.trim()) {
      message.warning('请填写要导入的知识文本');
      return;
    }
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
    if (!ragConfig.knowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    if (!knowledgeLocalFile) {
      message.warning('请选择本地 txt / markdown 文件');
      return;
    }
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
    if (!ragConfig.knowledgeBaseId) {
      message.warning('请先选择知识库');
      return;
    }
    setKnowledgeReindexing(true);
    try {
      const response = await rebuildKnowledgeBaseEmbeddings(ragConfig.knowledgeBaseId);
      if (response.code === 200) {
        const result = response.data;
        message.success(
          `向量索引重建完成：${result.chunkCount} 个切片，${result.embeddingProvider}/${result.embeddingModel}`
        );
        await refreshKnowledgeBases();
      } else {
        message.error(response.message || '向量索引重建失败');
      }
    } finally {
      setKnowledgeReindexing(false);
    }
  };

  // 添加 LLM 输出参数
  const handleAddLlmOutputParam = () => {
    setLlmOutputParams([...llmOutputParams, { name: '', type: 'string', description: '' }]);
  };

  // 删除 LLM 输出参数
  const handleRemoveLlmOutputParam = (index: number) => {
    setLlmOutputParams(llmOutputParams.filter((_, i) => i !== index));
  };

  // 更新 LLM 输出参数
  const handleUpdateLlmOutputParam = (index: number, field: keyof LlmOutputParam, value: string) => {
    const newParams = [...llmOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmOutputParams(newParams);
  };

  const selectedNodeType = String(selectedNode?.data?.type || '');
  const isGenericLlmNode = selectedNodeType === 'llm';
  const selectedNodeProvider = resolveSelectedNodeProvider(selectedNode);
  const availableLlmConfigs = isGenericLlmNode
    ? llmGlobalConfigs
    : llmGlobalConfigs.filter(
        (config) => normalizeProviderKey(config.provider) === selectedNodeProvider
      );

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* 顶部工具栏 */}
      <div className="bg-white shadow-sm px-6 py-4 flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h1 className="text-2xl font-bold text-gray-800">PaiAgent</h1>
          <Input
            value={workflowName}
            onChange={(e) => setWorkflowName(e.target.value)}
            className="w-64"
            placeholder="工作流名称"
            bordered={false}
            style={{ borderBottom: '2px solid #e5e7eb' }}
          />
          <Select
            value={engineType}
            onChange={(value) => setEngineType(value)}
            className="w-40"
            options={[
              { value: 'dag', label: 'DAG 引擎' },
              { value: 'langgraph', label: 'LangGraph 引擎' }
            ]}
          />
        </div>
        
        <div className="flex items-center gap-3">
          {role === 'ADMIN' && <LLMConfigModal />}
          <Button
            icon={<DatabaseOutlined />}
            onClick={() => navigate('/knowledge')}
            size="large"
          >
            知识库
          </Button>
          <Button
            icon={<PlusOutlined />}
            onClick={handleCreateNew}
            size="large"
          >
            新建
          </Button>
          <Button
            icon={<FolderOpenOutlined />}
            onClick={handleOpenLoadModal}
            size="large"
          >
            加载
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            onClick={handleSave}
            loading={saving}
            size="large"
          >
            保存
          </Button>
          <Button
            icon={<ShareAltOutlined />}
            onClick={handlePublishWorkflow}
            loading={publishing}
            size="large"
          >
            {publishInfo?.enabled ? '已发布' : '发布'}
          </Button>
          <Button
            icon={<ExperimentOutlined />}
            onClick={handleOpenHarness}
            loading={harnessLoading}
            size="large"
          >
            测试集
          </Button>
          <Button
            type="primary"
            icon={<BugOutlined />}
            onClick={handleOpenDebug}
            disabled={!currentWorkflowId}
            size="large"
          >
            调试
          </Button>
          <div className="ml-4 flex items-center gap-2 px-3 py-1 bg-gray-50 rounded-lg">
            <span className="text-gray-600">👤 {username}{role ? ` / ${role}` : ''}</span>
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
                registerDraftSaver={(saver) => {
                  selectedNodeDraftSaverRef.current = saver;
                }}
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
              <Input
                readOnly
                value={toAbsoluteUrl(publishInfo.publicPagePath)}
                addonBefore={<LinkOutlined />}
                addonAfter={
                  <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => copyText(toAbsoluteUrl(publishInfo.publicPagePath))}>
                    复制
                  </Button>
                }
              />
            </div>
            <div>
              <div className="text-sm text-gray-500 mb-2">API POST 调用地址</div>
              <Input
                readOnly
                value={toAbsoluteUrl(publishInfo.publicApiPath)}
                addonBefore={<ApiOutlined />}
                addonAfter={
                  <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => copyText(toAbsoluteUrl(publishInfo.publicApiPath))}>
                    复制
                  </Button>
                }
              />
              <div className="text-sm text-gray-500 mt-3 mb-2">API 访问密钥</div>
              <Input.Password
                readOnly
                value={publishInfo.apiAccessKey || ''}
                addonAfter={
                  <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => copyText(publishInfo.apiAccessKey || '')}>
                    复制
                  </Button>
                }
              />
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
