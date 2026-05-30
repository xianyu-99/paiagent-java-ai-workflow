import { Edge, Node } from '@xyflow/react';

const REACT_FLOW_NODE_TYPE = 'default';
const DEFAULT_ENTERPRISE_RAG_NODE_ID = 'rag-enterprise-kb';

export const ENTERPRISE_SERVICE_DESK_KNOWLEDGE_BASE_NAME = '企业服务台示例知识库';

const getFallbackLabel = (nodeType: string, nodeId: string) => {
  switch (nodeType) {
    case 'input':
      return '输入';
    case 'output':
      return '输出';
    case 'tts':
      return '语音合成';
    case 'condition':
      return '条件分支';
    case 'rag':
      return '知识库问答';
    case 'llm':
      return '大模型';
    default:
      return nodeType || nodeId;
  }
};

export const getWorkflowNodeType = (node: Pick<Node, 'type' | 'data'>) => {
  const dataType = typeof node.data?.type === 'string' ? node.data.type : '';
  if (dataType) {
    return dataType;
  }

  return node.type && node.type !== REACT_FLOW_NODE_TYPE ? node.type : '';
};

export const normalizeWorkflowNode = (node: Node): Node => {
  const workflowNodeType = getWorkflowNodeType(node);

  return {
    ...node,
    type: workflowNodeType === 'condition' ? 'condition' : REACT_FLOW_NODE_TYPE,
    data: {
      ...node.data,
      type: workflowNodeType,
      label: typeof node.data?.label === 'string' && node.data.label.trim()
        ? node.data.label
        : getFallbackLabel(workflowNodeType, node.id),
    },
  };
};

export const normalizeWorkflowNodes = (nodes: Node[]) => nodes.map(normalizeWorkflowNode);

export const serializeWorkflowNodes = (nodes: Node[]) =>
  nodes.map((node) => ({
    id: node.id,
    type: getWorkflowNodeType(node) || node.type,
    position: node.position,
    data: {
      ...node.data,
      type: getWorkflowNodeType(node) || node.type,
    },
  }));

export const bindDefaultEnterpriseKnowledgeBase = (
  nodes: Node[],
  enterpriseKnowledgeBaseId?: number
): Node[] => {
  if (!enterpriseKnowledgeBaseId) {
    return nodes;
  }

  let hasBoundKnowledgeBase = false;
  const boundNodes = nodes.map((node) => {
    const isDefaultEnterpriseRagNode =
      node.id === DEFAULT_ENTERPRISE_RAG_NODE_ID && getWorkflowNodeType(node) === 'rag';
    if (!isDefaultEnterpriseRagNode || node.data?.knowledgeBaseId) {
      return node;
    }

    hasBoundKnowledgeBase = true;
    return {
      ...node,
      data: {
        ...node.data,
        knowledgeBaseId: enterpriseKnowledgeBaseId,
      },
    };
  });

  return hasBoundKnowledgeBase ? boundNodes : nodes;
};

export const createDefaultWorkflowNodes = (enterpriseKnowledgeBaseId?: number): Node[] => [
  {
    id: 'input-default',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 80, y: 260 },
    data: {
      label: 'Input 用户问题',
      type: 'input',
    },
  },
  {
    id: 'rag-enterprise-kb',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 330, y: 260 },
    data: {
      label: 'RAG 检索企业知识库',
      type: 'rag',
      ...(enterpriseKnowledgeBaseId ? { knowledgeBaseId: enterpriseKnowledgeBaseId } : {}),
      retrievalOnly: true,
      topK: 4,
      minScore: 0,
      contextWindow: 1,
      contextMaxChars: 2400,
      inputParams: [{ name: 'question', type: 'reference', referenceNode: 'input-default.input' }],
      outputParams: [{ name: 'output', type: 'string' }],
    },
  },
  {
    id: 'llm-service-desk',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 610, y: 260 },
    data: {
      label: 'LLM 生成带引用答案',
      type: 'llm',
      skillName: 'service-desk-answer',
      temperature: 0.2,
      prompt:
        '请基于用户问题、RAG 上下文和引用来源生成企业服务台处理结果。用户问题：{{question}}。RAG 上下文：{{context}}。引用来源：{{citations}}。只输出固定 JSON 字段：answer、citations、confidence、resolved、nextAction、ticketSummary、escalationReason。',
      inputParams: [
        { name: 'question', type: 'reference', referenceNode: 'input-default.input' },
        { name: 'context', type: 'reference', referenceNode: 'rag-enterprise-kb.context' },
        { name: 'citations', type: 'reference', referenceNode: 'rag-enterprise-kb.citations' },
      ],
      outputParams: [{ name: 'output', type: 'object', description: '企业服务台结构化 JSON' }],
    },
  },
  {
    id: 'condition-high-confidence',
    type: 'condition',
    position: { x: 900, y: 260 },
    data: {
      label: '置信度 >= 0.80',
      type: 'condition',
      leftType: 'reference',
      leftReference: 'llm-service-desk.confidence',
      operator: 'gte',
      rightValue: '0.8',
      caseSensitive: false,
    },
  },
  {
    id: 'condition-direct-action',
    type: 'condition',
    position: { x: 1180, y: 80 },
    data: {
      label: '动作：直接答复',
      type: 'condition',
      leftType: 'reference',
      leftReference: 'llm-service-desk.nextAction',
      operator: 'equals',
      rightValue: 'direct_answer',
      caseSensitive: false,
    },
  },
  {
    id: 'condition-ticket-action',
    type: 'condition',
    position: { x: 1180, y: 440 },
    data: {
      label: '动作：生成工单',
      type: 'condition',
      leftType: 'reference',
      leftReference: 'llm-service-desk.nextAction',
      operator: 'equals',
      rightValue: 'create_ticket',
      caseSensitive: false,
    },
  },
  {
    id: 'output-direct-answer',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 1470, y: 80 },
    data: {
      label: 'Output 直接回答',
      type: 'output',
      outputParams: [{ name: 'answerPayload', type: 'reference', referenceNode: 'llm-service-desk.output' }],
      responseContent: '{{answerPayload}}',
    },
  },
  {
    id: 'output-create-ticket',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 1470, y: 350 },
    data: {
      label: 'Output 工单摘要',
      type: 'output',
      outputParams: [{ name: 'answerPayload', type: 'reference', referenceNode: 'llm-service-desk.output' }],
      responseContent: '{{answerPayload}}',
    },
  },
  {
    id: 'output-escalate-human',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 1470, y: 560 },
    data: {
      label: 'Output 升级人工',
      type: 'output',
      outputParams: [{ name: 'answerPayload', type: 'reference', referenceNode: 'llm-service-desk.output' }],
      responseContent: '{{answerPayload}}',
    },
  },
];

export const createDefaultWorkflowEdges = (): Edge[] => [
  {
    id: 'edge-input-rag',
    source: 'input-default',
    target: 'rag-enterprise-kb',
  },
  {
    id: 'edge-rag-llm',
    source: 'rag-enterprise-kb',
    target: 'llm-service-desk',
  },
  {
    id: 'edge-llm-confidence',
    source: 'llm-service-desk',
    target: 'condition-high-confidence',
  },
  {
    id: 'edge-confidence-direct-action',
    source: 'condition-high-confidence',
    target: 'condition-direct-action',
    sourceHandle: 'true',
  },
  {
    id: 'edge-confidence-ticket-action',
    source: 'condition-high-confidence',
    target: 'condition-ticket-action',
    sourceHandle: 'false',
  },
  {
    id: 'edge-direct-action-answer',
    source: 'condition-direct-action',
    target: 'output-direct-answer',
    sourceHandle: 'true',
  },
  {
    id: 'edge-direct-action-ticket-action',
    source: 'condition-direct-action',
    target: 'condition-ticket-action',
    sourceHandle: 'false',
  },
  {
    id: 'edge-ticket-action-ticket',
    source: 'condition-ticket-action',
    target: 'output-create-ticket',
    sourceHandle: 'true',
  },
  {
    id: 'edge-ticket-action-escalation',
    source: 'condition-ticket-action',
    target: 'output-escalate-human',
    sourceHandle: 'false',
  },
];
