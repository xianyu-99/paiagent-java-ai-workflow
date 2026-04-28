import { useState, useEffect, useRef, useCallback } from 'react';
import { Button, Input, Form, message, Checkbox, Select, Modal, List, Tabs, Upload, Space } from 'antd';
import { SaveOutlined, FolderOpenOutlined, BugOutlined, LogoutOutlined, PlusOutlined, DeleteOutlined, UploadOutlined, DatabaseOutlined, ShareAltOutlined, CopyOutlined, LinkOutlined, ApiOutlined } from '@ant-design/icons';
import { Edge, MarkerType, Node } from '@xyflow/react';
import NodePanel from '../components/NodePanel';
import FlowCanvas from '../components/FlowCanvas';
import DebugDrawer from '../components/DebugDrawer';
import SkillSelector from '../components/SkillSelector';
import LLMConfigModal from '../components/LLMConfigModal';
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
  Workflow,
  WorkflowPublish,
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
  getProviderFromNodeType,
  getProviderLabel,
  getSupportedProviderOptions,
  isLlmNodeType,
  normalizeProviderKey,
} from '../utils/provider';
import { createDefaultWorkflowNodes, normalizeWorkflowNodes, serializeWorkflowNodes } from '../utils/workflowNode';
import { useNavigate, useParams } from 'react-router-dom';

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

/**
 * 工作流编辑器页面
 */
const EditorPage = () => {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const { username, role, clearAuth } = useAuthStore();
  const { nodes, edges, currentWorkflowId, setCurrentWorkflowId, selectedNode, setNodes, setEdges } = useWorkflowStore();
  const [workflowName, setWorkflowName] = useState('未命名工作流');
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
  const hasLoadedRef = useRef<number | null>(null);
  
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
    apiKey: '',
    model: 'qwen3-tts-flash',
    voice: 'Cherry',
    languageType: 'Auto',
    apiKeyConfigured: false
  });
  const [ttsInputParams, setTtsInputParams] = useState<TtsInputParam[]>([]);
  const [ttsOutputParams, setTtsOutputParams] = useState<TtsOutputParam[]>([]);
  const [conditionConfig, setConditionConfig] = useState<ConditionConfig>({
    leftType: 'reference',
    leftValue: '',
    leftReference: 'input-default.user_input',
    operator: 'contains',
    rightValue: '',
    caseSensitive: false
  });
  const [ragConfig, setRagConfig] = useState<RagConfig>({
    knowledgeBaseId: undefined,
    configId: undefined,
    questionReference: 'input-default.user_input',
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

  // 自动保存定时器
  const autoSaveTimerRef = useRef<number | null>(null);

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

  // 处理节点拖拽开始
  const handleDragStart = (event: React.DragEvent, nodeType: string, displayName: string) => {
    event.dataTransfer.setData('application/reactflow-type', nodeType);
    event.dataTransfer.setData('application/reactflow-label', displayName);
    event.dataTransfer.effectAllowed = 'move';
  };

  // 处理节点点击
  const handleNodeClick = (node: Node) => {
    console.log('Node clicked:', node);

    // 清理之前的自动保存定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
      autoSaveTimerRef.current = null;
    }

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
        apiKey: (node.data?.apiKey as string) || '',
        model: (node.data?.model as string) || 'qwen3-tts-flash',
        voice: (node.data?.voice as string) || 'Cherry',
        languageType: (node.data?.languageType as string) || 'Auto',
        apiKeyConfigured: Boolean(node.data?.apiKeyConfigured || node.data?.apiKey)
      });
      setTtsInputParams((node.data?.inputParams as TtsInputParam[]) || []);
      setTtsOutputParams((node.data?.outputParams as TtsOutputParam[]) || []);
    } else if (node.data?.type === 'condition') {
      setConditionConfig({
        leftType: (node.data?.leftType as 'input' | 'reference') || 'reference',
        leftValue: (node.data?.leftValue as string) || '',
        leftReference: (node.data?.leftReference as string) || 'input-default.user_input',
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
        questionReference: questionParam?.referenceNode || 'input-default.user_input',
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
    return persistWorkflowSnapshot(nodes, edges);
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
      case 'openai':
      case 'deepseek':
      case 'qwen':
      case 'step':
      case 'zhipu':
      case 'ai_ping':
        return ['output', 'tokens'];
      case 'tts':
        return ['audioUrl', 'fileName', 'output'];
      case 'condition':
        return ['conditionResult', 'selectedBranch', 'output'];
      case 'rag':
        return ['output', 'context', 'retrievedCount'];
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
    setWorkflowName('未命名工作流');
    
    // 创建默认的输入和输出节点(上下排列)
    setNodes(createDefaultWorkflowNodes());
    setEdges([]);
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
      apiKey: ttsConfig.apiKey,
      model: ttsConfig.model,
      voice: ttsConfig.voice,
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
    if (!ragConfig.configId) {
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

  // 自动保存输出节点配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'output') return;
    
    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    
    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证
      let hasValidData = false;
      
      // 检查是否有有效的参数配置
      if (outputParams.length > 0) {
        hasValidData = outputParams.some(param => param.name && (param.value || param.referenceNode));
      }
      
      // 或者有响应内容
      if (responseContent && responseContent.trim()) {
        hasValidData = true;
      }
      
      if (!hasValidData) return; // 没有有效数据，不保存
      
      // 保存到节点的 data 中
      const updatedData = {
        ...selectedNode.data,
        outputParams,
        responseContent
      };
      
      useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
      console.log('输出节点配置已自动保存');
    }, 500);
    
    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [outputParams, responseContent, selectedNode]);

  // 自动保存 LLM 节点配置
  useEffect(() => {
    if (!selectedNode) return;
    const nodeType = selectedNode.data?.type;
    if (!isLlmNodeType(String(nodeType || ''))) return;

    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }

    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证：至少有基本配置
      const hasBasicConfig = llmConfig.configId || llmConfig.apiUrl || llmConfig.apiKey || llmConfig.model || llmConfig.prompt;
      const hasParams = llmInputParams.length > 0 || llmOutputParams.length > 0;

      if (!hasBasicConfig && !hasParams) return; // 没有任何配置，不保存

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
      console.log('LLM节点配置已自动保存');
    }, 500);

    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [llmConfig, llmInputParams, llmOutputParams, selectedNode]);

  // 自动保存 TTS 节点配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'tts') return;
    
    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    
    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证：至少有基本配置
      const hasBasicConfig = ttsConfig.apiKey || ttsConfig.model;
      const hasParams = ttsInputParams.length > 0 || ttsOutputParams.length > 0;
      
      if (!hasBasicConfig && !hasParams) return; // 没有任何配置，不保存
      
      const updatedData = {
        ...selectedNode.data,
        apiKey: ttsConfig.apiKey,
        model: ttsConfig.model,
        voice: ttsConfig.voice,
        languageType: ttsConfig.languageType,
        inputParams: ttsInputParams,
        outputParams: ttsOutputParams
      };
      
      useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
      console.log('TTS节点配置已自动保存');
    }, 500);
    
    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [ttsConfig, ttsInputParams, ttsOutputParams, selectedNode]);

  // 自动保存条件节点配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'condition') return;

    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }

    autoSaveTimerRef.current = setTimeout(() => {
      const hasLeft = conditionConfig.leftType === 'reference'
        ? !!conditionConfig.leftReference
        : !!conditionConfig.leftValue;
      const hasRight = ['empty', 'not_empty'].includes(conditionConfig.operator) || !!conditionConfig.rightValue;

      if (!hasLeft || !hasRight) return;

      useWorkflowStore.getState().updateNode(selectedNode.id, {
        ...selectedNode.data,
        ...conditionConfig,
      });
      console.log('条件节点配置已自动保存');
    }, 500);

    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [conditionConfig, selectedNode]);

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
                
                {/* 输入节点配置 */}
                {selectedNode.data?.type === 'input' && (
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
                )}

                {/* 输出节点配置 */}
                {selectedNode.data?.type === 'output' && (
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
                              onChange={(value) => handleUpdateOutputParam(index, 'type', value)}
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
                                onChange={(value) => handleUpdateOutputParam(index, 'referenceNode', value)}
                                className="w-full"
                              >
                                {getReferenceableParams().map(param => (
                                  <Select.Option key={param.value} value={param.value}>
                                    {param.label}
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
                )}

                {/* LLM 节点配置 */}
                {isLlmNodeType(selectedNodeType) && (
                  <Form layout="vertical" className="mt-4">
                    {/* 输入参数配置 */}
                    <div className="mb-6">
                      <div className="flex justify-between items-center mb-3">
                        <label className="font-medium text-gray-700">输入参数</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddLlmInputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {llmInputParams.map((param, index) => (
                        <div key={index} className="flex items-start gap-2 mb-3">
                          <Input 
                            placeholder="参数名"
                            value={param.name}
                            onChange={(e) => handleUpdateLlmInputParam(index, 'name', e.target.value)}
                            style={{ width: '90px' }}
                          />
                          <Select
                            value={param.type}
                            onChange={(value) => handleUpdateLlmInputParam(index, 'type', value)}
                            style={{ width: '70px' }}
                          >
                            <Select.Option value="input">输入</Select.Option>
                            <Select.Option value="reference">引用</Select.Option>
                          </Select>
                          <div className="flex-1">
                            {param.type === 'input' ? (
                              <Input 
                                placeholder="输入值"
                                value={param.value}
                                onChange={(e) => handleUpdateLlmInputParam(index, 'value', e.target.value)}
                              />
                            ) : (
                              <Select
                                placeholder="选择参数"
                                value={param.referenceNode}
                                onChange={(value) => handleUpdateLlmInputParam(index, 'referenceNode', value)}
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
                            onClick={() => handleRemoveLlmInputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {llmInputParams.length === 0 && (
                        <div className="text-gray-400 text-center py-4 border border-dashed border-gray-300 rounded">
                          点击"添加"按钮添加输入参数
                        </div>
                      )}
                    </div>

                    {/* 输出参数配置 */}
                    <div className="mb-6">
                      <div className="flex justify-between items-center mb-3">
                        <label className="font-medium text-gray-700">输出参数</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddLlmOutputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {llmOutputParams.map((param, index) => (
                        <div key={index} className="flex items-start gap-2 mb-3">
                          <Input 
                            placeholder="变量名"
                            value={param.name}
                            onChange={(e) => handleUpdateLlmOutputParam(index, 'name', e.target.value)}
                            style={{ width: '100px' }}
                          />
                          <Input
                            value="string"
                            disabled
                            style={{ width: '70px' }}
                          />
                          <div className="flex-1">
                            <Input 
                              placeholder="描述（可选）"
                              value={param.description}
                              onChange={(e) => handleUpdateLlmOutputParam(index, 'description', e.target.value)}
                            />
                          </div>
                          <Button 
                            type="text" 
                            danger 
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveLlmOutputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {llmOutputParams.length === 0 && (
                        <div className="text-gray-400 text-center py-4 border border-dashed border-gray-300 rounded">
                          点击"添加"按钮添加输出参数
                        </div>
                      )}
                    </div>

                    <Form.Item label="提示词模板" required>
                      <Input.TextArea
                        rows={12}
                        placeholder="输入提示词模板，使用 {{参数名}} 引用输入参数"
                        value={llmConfig.prompt}
                        onChange={(e) => setLlmConfig({...llmConfig, prompt: e.target.value})}
                        style={{ fontFamily: 'monospace', fontSize: '12px' }}
                      />
                      <div className="text-xs text-gray-500 mt-1">
                        💡 使用 {'{{'} 参数名 {'}'} 引用上面定义的输入参数
                      </div>
                    </Form.Item>

                    {/* 全局配置选择 */}
                    <Form.Item
                      label="全局配置"
                      required={!llmConfig.configId && !llmConfig.apiUrl}
                    >
                      <Select
                        value={llmConfig.configId}
                        onChange={(value) => {
                          if (value) {
                            const config = llmGlobalConfigs.find(c => c.id === value);
                            if (config) {
                              setLlmConfig({
                                ...llmConfig,
                                provider: normalizeProviderKey(config.provider),
                                configId: value,
                                apiUrl: config.apiUrl,
                                apiKey: '',
                                model: config.model,
                                temperature: config.temperature
                              });
                            }
                          } else {
                            setLlmConfig({
                              ...llmConfig,
                              provider: isGenericLlmNode ? '' : selectedNodeProvider,
                              configId: undefined,
                              apiUrl: '',
                              apiKey: '',
                              model: '',
                              temperature: 0.7
                            });
                          }
                        }}
                        placeholder="选择一个全局配置"
                        allowClear
                      >
                        {availableLlmConfigs.map(config => (
                            <Select.Option key={config.id} value={config.id}>
                              {isGenericLlmNode ? `${getProviderLabel(config.provider)} / ` : ''}
                              {config.configName} {config.isDefault === 1 ? '(该供应商默认)' : ''}
                            </Select.Option>
                          ))}
                      </Select>
                      <div className="text-xs text-gray-500 mt-1">
                        💡 选择全局配置后无需填写下方 API 信息
                      </div>
                    </Form.Item>

                    {/* API 配置（未选择全局配置时显示） */}
                    {!llmConfig.configId && (
                      <>
                        {isGenericLlmNode && (
                          <Form.Item label="供应商" required>
                            <Select
                              value={llmConfig.provider || undefined}
                              placeholder="选择这条节点配置要使用的供应商"
                              options={providerOptions}
                              onChange={(value) => setLlmConfig({ ...llmConfig, provider: value })}
                            />
                          </Form.Item>
                        )}
                        <div className="text-xs text-orange-500 mb-2 px-2 py-1 bg-orange-50 rounded">
                          ⚠️ 未选择全局配置，请手动填写以下 API 信息
                        </div>
                        <Form.Item label="API 地址" required>
                          <Input
                            placeholder="例如: https://dashscope.aliyuncs.com/compatible-mode"
                            value={llmConfig.apiUrl}
                            onChange={(e) => setLlmConfig({...llmConfig, apiUrl: e.target.value})}
                          />
                          <div className="text-xs text-gray-500 mt-1">
                            填写兼容接口根地址，不要追加 <code>/v1</code> 或 <code>/v1/chat/completions</code>
                          </div>
                        </Form.Item>
                        <Form.Item label="API 密钥" required>
                          <Input.Password
                            placeholder="输入 API Key"
                            value={llmConfig.apiKey}
                            onChange={(e) => setLlmConfig({...llmConfig, apiKey: e.target.value})}
                          />
                        </Form.Item>
                        <Form.Item label="模型名称" required>
                          <Input
                            placeholder="例如: deepseek-chat, claude-3-5-sonnet-20241022"
                            value={llmConfig.model}
                            onChange={(e) => setLlmConfig({...llmConfig, model: e.target.value})}
                          />
                        </Form.Item>
                        <Form.Item label="温度">
                          <Input
                            type="number"
                            step="0.1"
                            min="0"
                            max="2"
                            value={llmConfig.temperature}
                            onChange={(e) => setLlmConfig({...llmConfig, temperature: parseFloat(e.target.value) || 0.7})}
                          />
                          <div className="text-xs text-gray-500 mt-1">
                            控制输出随机性，范围 0-2，值越高越随机
                          </div>
                        </Form.Item>
                      </>
                    )}

                    {/* 已选择全局配置时显示配置信息 */}
                    {llmConfig.configId && (
                      <div className="text-xs text-gray-500 mb-4 p-3 bg-gray-50 rounded">
                        <div className="font-medium mb-2">当前使用全局配置：</div>
                        <div>供应商: {getProviderLabel(llmConfig.provider)}</div>
                        <div>API 地址: {llmConfig.apiUrl}</div>
                        <div>模型: {llmConfig.model}</div>
                        <div>温度: {llmConfig.temperature}</div>
                      </div>
                    )}

                    <Form.Item label="技能 (Skill)">
                      <SkillSelector
                        value={llmConfig.skillName}
                        onChange={(value) => setLlmConfig({...llmConfig, skillName: value || ''})}
                      />
                      <div className="text-xs text-gray-500 mt-1">
                        选择一个技能来增强 LLM 的能力，LLM 会自动获取技能指南
                      </div>
                    </Form.Item>
                    <Button type="primary" block onClick={handleSaveLlmConfig}>
                      保存配置
                    </Button>
                  </Form>
                )}

                {/* TTS 节点配置 (超拟人音频) */}
                {selectedNode.data?.type === 'tts' && (
                  <Form layout="vertical" className="mt-4">
                    {/* 输入配置 */}
                    <div className="mb-6">
                      <div className="flex justify-between items-center mb-3">
                        <label className="font-medium text-gray-700">输入配置</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddTtsInputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {ttsInputParams.map((param, index) => (
                        <div key={index} className="mb-4 p-3 bg-gray-50 rounded">
                          <div className="flex items-center gap-2 mb-2">
                            <Input 
                              placeholder="参数名 (如: text)"
                              value={param.name}
                              onChange={(e) => handleUpdateTtsInputParam(index, 'name', e.target.value)}
                              style={{ width: 120 }}
                            />
                            <Select
                              value={param.type}
                              onChange={(value) => handleUpdateTtsInputParam(index, 'type', value)}
                              style={{ width: 100 }}
                            >
                              <Select.Option value="input">输入</Select.Option>
                              <Select.Option value="reference">引用</Select.Option>
                            </Select>
                            <Button 
                              type="text" 
                              danger 
                              icon={<DeleteOutlined />}
                              onClick={() => handleRemoveTtsInputParam(index)}
                            />
                          </div>
                          <div>
                            {param.type === 'input' ? (
                              <Input.TextArea
                                placeholder="输入值"
                                value={param.value}
                                onChange={(e) => handleUpdateTtsInputParam(index, 'value', e.target.value)}
                                rows={2}
                              />
                            ) : (
                              <Select
                                placeholder="选择引用参数"
                                value={param.referenceNode}
                                onChange={(value) => handleUpdateTtsInputParam(index, 'referenceNode', value)}
                                style={{ width: '100%' }}
                              >
                                {getReferenceableParams().map((p) => (
                                  <Select.Option key={p.value} value={p.value}>
                                    {p.label}
                                  </Select.Option>
                                ))}
                              </Select>
                            )}
                          </div>
                        </div>
                      ))}
                      
                      {ttsInputParams.length === 0 && (
                        <div className="text-center py-4 text-gray-400 text-sm border border-dashed rounded">
                          暂无输入参数,点击"添加"按钮创建 text 参数
                        </div>
                      )}
                      
                      {/* 固定配置项 */}
                      <div className="mt-4 space-y-3">
                        <Form.Item label="音色 (voice)" className="mb-0">
                          <Select
                            value={ttsConfig.voice}
                            onChange={(value) => setTtsConfig({ ...ttsConfig, voice: value })}
                          >
                            <Select.Option value="Cherry">Cherry (芊悦)</Select.Option>
                            <Select.Option value="Serena">Serena (苏瑶)</Select.Option>
                            <Select.Option value="Ethan">Ethan (晨煦)</Select.Option>
                            <Select.Option value="Chelsie">Chelsie (千雪)</Select.Option>
                            <Select.Option value="Momo">Momo (茉兔)</Select.Option>
                            <Select.Option value="Vivian">Vivian (十三)</Select.Option>
                            <Select.Option value="Moon">Moon (月白)</Select.Option>
                            <Select.Option value="Maia">Maia (四月)</Select.Option>
                            <Select.Option value="Kai">Kai (凯)</Select.Option>
                            <Select.Option value="Nofish">Nofish (不吃鱼)</Select.Option>
                            <Select.Option value="Bella">Bella (萌宝)</Select.Option>
                            <Select.Option value="Jennifer">Jennifer (詹妮弗)</Select.Option>
                            <Select.Option value="Ryan">Ryan (甜茶)</Select.Option>
                            <Select.Option value="Katerina">Katerina (卡捷琳娜)</Select.Option>
                            <Select.Option value="Aiden">Aiden (艾登)</Select.Option>
                          </Select>
                        </Form.Item>

                        <Form.Item label="语言类型 (language_type)" className="mb-0">
                          <Select
                            value={ttsConfig.languageType}
                            onChange={(value) => setTtsConfig({ ...ttsConfig, languageType: value })}
                          >
                            <Select.Option value="Auto">Auto</Select.Option>
                          </Select>
                        </Form.Item>
                      </div>
                    </div>

                    {/* 输出配置 */}
                    <div className="mb-6">
                      <div className="flex justify-between items-center mb-3">
                        <label className="font-medium text-gray-700">输出配置</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddTtsOutputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {ttsOutputParams.map((param, index) => (
                        <div key={index} className="flex items-center gap-2 mb-3">
                          <Input 
                            placeholder="参数名 (如: voice_url)"
                            value={param.name}
                            onChange={(e) => handleUpdateTtsOutputParam(index, 'name', e.target.value)}
                            style={{ flex: 1 }}
                          />
                          <Input
                            placeholder="参数值 (引用字段,如: audioUrl)"
                            value={param.value}
                            onChange={(e) => handleUpdateTtsOutputParam(index, 'value', e.target.value)}
                            style={{ flex: 1 }}
                          />
                          <Button 
                            type="text" 
                            danger 
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveTtsOutputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {ttsOutputParams.length === 0 && (
                        <div className="text-center py-4 text-gray-400 text-sm border border-dashed rounded">
                          暂无输出参数,点击"添加"按钮创建 voice_url 参数
                        </div>
                      )}
                    </div>

                    {/* 基本信息 */}
                    <div className="mb-4">
                      <label className="font-medium text-gray-700 block mb-3">基本信息</label>
                      <Form.Item label="API Key">
                        <Input.Password
                          placeholder={ttsConfig.apiKeyConfigured ? '留空则沿用已保存的 API Key' : '请输入阿里百炼 API Key'}
                          value={ttsConfig.apiKey}
                          onChange={(e) => setTtsConfig({ ...ttsConfig, apiKey: e.target.value })}
                        />
                      </Form.Item>
                      <Form.Item label="模型名称">
                        <Input
                          placeholder="请输入模型名称"
                          value={ttsConfig.model}
                          onChange={(e) => setTtsConfig({ ...ttsConfig, model: e.target.value })}
                        />
                      </Form.Item>
                    </div>

                    <Button type="primary" block onClick={handleSaveTtsConfig}>
                      保存配置
                    </Button>
                  </Form>
                )}

                {/* 条件分支节点配置 */}
                {selectedNode.data?.type === 'condition' && (
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
                          onChange={(value) => setConditionConfig({ ...conditionConfig, leftReference: value })}
                          style={{ width: '100%' }}
                        >
                          {[{ label: '运行时.loopIteration', value: 'loopIteration' }, ...getReferenceableParams()].map((p) => (
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
                        onChange={(value) => setConditionConfig({ ...conditionConfig, operator: value })}
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
                )}

                {/* RAG 知识库节点配置 */}
                {selectedNode.data?.type === 'rag' && (
                  <Form layout="vertical" className="mt-4">
                    <div className="mb-4 p-3 bg-blue-50 rounded text-sm text-blue-700">
                      RAG 节点会先检索知识库片段，再把上下文和问题交给 LLM 生成答案。
                    </div>

                    <Form.Item label="知识库" required>
                      <Select
                        placeholder="选择知识库"
                        value={ragConfig.knowledgeBaseId}
                        onChange={(value) => setRagConfig({ ...ragConfig, knowledgeBaseId: value })}
                        style={{ width: '100%' }}
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
                      <Input
                        className="mb-2"
                        placeholder="知识库名称"
                        value={newKnowledgeBaseName}
                        onChange={(e) => setNewKnowledgeBaseName(e.target.value)}
                      />
                      <Input
                        className="mb-2"
                        placeholder="知识库描述，可选"
                        value={newKnowledgeBaseDescription}
                        onChange={(e) => setNewKnowledgeBaseDescription(e.target.value)}
                      />
                      <Button block onClick={handleCreateKnowledgeBase}>
                        创建并选中
                      </Button>
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
                                <Input
                                  className="mb-2"
                                  placeholder="文件名，如 faq.md"
                                  value={knowledgeFileName}
                                  onChange={(e) => setKnowledgeFileName(e.target.value)}
                                />
                                <Input.TextArea
                                  rows={5}
                                  placeholder="粘贴 txt / markdown 文本，后端会自动切片并向量化"
                                  value={knowledgeContent}
                                  onChange={(e) => setKnowledgeContent(e.target.value)}
                                />
                                <Button className="mt-2" block onClick={handleUploadKnowledgeDocument}>
                                  上传并切片
                                </Button>
                              </>
                            )
                          },
                          {
                            key: 'file',
                            label: '本地文件',
                            children: (
                              <>
                                <Upload
                                  accept=".txt,.md,.markdown,.json,.pdf,.doc,.docx,text/plain,text/markdown,application/json,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                  maxCount={1}
                                  beforeUpload={(file) => {
                                    setKnowledgeLocalFile(file);
                                    return false;
                                  }}
                                  onRemove={() => {
                                    setKnowledgeLocalFile(null);
                                  }}
                                  fileList={knowledgeLocalFile ? [{
                                    uid: knowledgeLocalFile.name,
                                    name: knowledgeLocalFile.name,
                                    status: 'done'
                                  }] : []}
                                >
                                  <Button icon={<UploadOutlined />} block>
                                    选择本地 txt / md / json / pdf / doc / docx 文件
                                  </Button>
                                </Upload>
                                <Button
                                  className="mt-2"
                                  block
                                  loading={knowledgeFileUploading}
                                  onClick={handleUploadKnowledgeLocalFile}
                                >
                                  上传文件并切片
                                </Button>
                              </>
                            )
                          }
                        ]}
                      />
                      <Button
                        className="mt-2"
                        block
                        loading={knowledgeReindexing}
                        onClick={handleRebuildKnowledgeEmbeddings}
                      >
                        重建向量索引
                      </Button>
                    </div>

                    <Form.Item label="问题来源" required>
                      <Select
                        placeholder="选择用户问题来源"
                        value={ragConfig.questionReference}
                        onChange={(value) => setRagConfig({ ...ragConfig, questionReference: value })}
                        style={{ width: '100%' }}
                      >
                        {getReferenceableParams().map((p) => (
                          <Select.Option key={p.value} value={p.value}>
                            {p.label}
                          </Select.Option>
                        ))}
                      </Select>
                    </Form.Item>

                    <Form.Item label="LLM 全局配置" required>
                      <Select
                        placeholder="选择用于生成答案的模型配置"
                        value={ragConfig.configId}
                        onChange={(value) => setRagConfig({ ...ragConfig, configId: value })}
                        style={{ width: '100%' }}
                      >
                        {llmGlobalConfigs.map(config => (
                          <Select.Option key={config.id} value={config.id}>
                            {getProviderLabel(config.provider)} / {config.configName}
                          </Select.Option>
                        ))}
                      </Select>
                    </Form.Item>

                    <div className="grid grid-cols-2 gap-3">
                      <Form.Item label="召回数量 topK">
                        <Input
                          type="number"
                          min={1}
                          max={10}
                          value={ragConfig.topK}
                          onChange={(e) => setRagConfig({ ...ragConfig, topK: Number(e.target.value || 3) })}
                        />
                      </Form.Item>
                      <Form.Item label="最低相似度">
                        <Input
                          type="number"
                          min={0}
                          max={1}
                          step={0.01}
                          value={ragConfig.minScore}
                          onChange={(e) => setRagConfig({ ...ragConfig, minScore: Number(e.target.value || 0) })}
                        />
                      </Form.Item>
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                      <Form.Item label="上下文窗口">
                        <Input
                          type="number"
                          min={0}
                          max={2}
                          value={ragConfig.contextWindow}
                          onChange={(e) => setRagConfig({ ...ragConfig, contextWindow: Number(e.target.value || 1) })}
                        />
                      </Form.Item>
                      <Form.Item label="单条上下文字符">
                        <Input
                          type="number"
                          min={400}
                          max={6000}
                          step={100}
                          value={ragConfig.contextMaxChars}
                          onChange={(e) => setRagConfig({ ...ragConfig, contextMaxChars: Number(e.target.value || 1800) })}
                        />
                      </Form.Item>
                    </div>

                    <Form.Item label="回答提示词">
                      <Input.TextArea
                        rows={6}
                        placeholder="留空使用默认 RAG 提示词；可使用 {{context}} 和 {{question}}"
                        value={ragConfig.prompt}
                        onChange={(e) => setRagConfig({ ...ragConfig, prompt: e.target.value })}
                      />
                    </Form.Item>

                    <Button type="primary" block onClick={handleSaveRagConfig}>
                      保存配置
                    </Button>
                  </Form>
                )}

                {/* 其他节点配置 */}
                {selectedNode.data?.type !== 'input' &&
                 selectedNode.data?.type !== 'output' &&
                 !isLlmNodeType(selectedNodeType) &&
                 selectedNode.data?.type !== 'tts' &&
                 selectedNode.data?.type !== 'condition' &&
                 selectedNode.data?.type !== 'rag' && (
                  <div className="mt-4 text-center text-gray-400 text-sm">
                    该节点暂无可配置项
                  </div>
                )}
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
              <div className="text-xs text-gray-500 mt-2">
                这是给程序调用的接口，浏览器地址栏不能直接打开。POST JSON: {`{ "inputData": "你的输入" }`}
              </div>
            </div>
            <Button type="primary" onClick={() => window.open(toAbsoluteUrl(publishInfo.publicPagePath), '_blank')}>
              打开公开页面
            </Button>
          </Space>
        ) : (
          <div className="text-gray-500">当前工作流尚未发布。</div>
        )}
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
