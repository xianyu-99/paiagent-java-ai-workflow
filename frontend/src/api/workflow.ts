import api from '../utils/request';
import { buildBackendUrl } from '../config/api';
import { clearStoredAuth } from '../utils/auth';

export interface NodeDefinition {
  id: number;
  nodeType: string;
  displayName: string;
  category: string;
  icon: string;
  inputSchema: string;
  outputSchema: string;
  configSchema: string;
}

export interface WorkflowData {
  name: string;
  description?: string;
  flowData: string;
  engineType?: string;
}

export interface Workflow {
  id: number;
  name: string;
  description: string;
  flowData: string;
  engineType?: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowPublish {
  id: number;
  workflowId: number;
  shareKey: string;
  apiAccessKey?: string;
  title: string;
  description?: string;
  enabled: boolean;
  publicPagePath: string;
  publicApiPath: string;
  createdAt: string;
  updatedAt: string;
}

export interface PublishedWorkflowInfo {
  workflowId: number;
  shareKey: string;
  title: string;
  description?: string;
  nodeSummary?: string;
  publicApiPath: string;
}

export interface ExecutionNodeResult {
  nodeId: string;
  nodeName?: string;
  status: string;
  input?: string;
  output?: string;
  duration?: number;
  error?: string;
}

export interface ExecutionResponse {
  executionId?: number;
  status: string;
  nodeResults?: ExecutionNodeResult[];
  outputData?: unknown;
  duration?: number;
  errorMessage?: string;
  errorLog?: unknown;
}

export interface WorkflowTestCase {
  id: number;
  workflowId: number;
  name: string;
  inputData: string;
  expectedContains: string[];
  expectedNotContains: string[];
  expectedStatus: string;
  requireCitation: boolean;
  requireAudio: boolean;
  maxDurationMs?: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface WorkflowTestCaseRequest {
  name: string;
  inputData: string;
  expectedContains?: string[];
  expectedNotContains?: string[];
  expectedStatus?: string;
  requireCitation?: boolean;
  requireAudio?: boolean;
  maxDurationMs?: number;
  enabled?: boolean;
}

export interface WorkflowTestAssertion {
  type: string;
  passed: boolean;
  message: string;
}

export interface WorkflowTestResult {
  id: number;
  runId: number;
  caseId: number;
  caseName: string;
  status: 'PASSED' | 'FAILED';
  actualOutput?: string;
  assertionResults?: WorkflowTestAssertion[];
  executionId?: number;
  duration?: number;
  errorMessage?: string;
  createdAt?: string;
}

export interface WorkflowTestRun {
  id: number;
  workflowId: number;
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  totalCount: number;
  passedCount: number;
  failedCount: number;
  duration: number;
  createdBy?: number;
  results?: WorkflowTestResult[];
  createdAt?: string;
  updatedAt?: string;
}

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface WorkflowExecutionNodeData {
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
}

export interface WorkflowExecutionPayload {
  inputData?: string;
  outputData?: unknown;
  nodeResults?: unknown[];
}

/**
 * 获取所有节点类型
 */
export const getNodeTypes = (): Promise<ApiResult<NodeDefinition[]>> => {
  return api.get('/api/node-types');
};

/**
 * 创建工作流
 */
export const createWorkflow = (data: WorkflowData): Promise<ApiResult<Workflow>> => {
  return api.post('/api/workflows', data);
};

/**
 * 获取工作流列表
 */
export const getWorkflows = (): Promise<ApiResult<Workflow[]>> => {
  return api.get('/api/workflows');
};

/**
 * 获取工作流详情
 */
export const getWorkflow = (id: number): Promise<ApiResult<Workflow>> => {
  return api.get(`/api/workflows/${id}`);
};

/**
 * 更新工作流
 */
export const updateWorkflow = (id: number, data: WorkflowData): Promise<ApiResult<Workflow>> => {
  return api.put(`/api/workflows/${id}`, data);
};

/**
 * 删除工作流
 */
export const deleteWorkflow = (id: number): Promise<ApiResult<void>> => {
  return api.delete(`/api/workflows/${id}`);
};

/**
 * 执行工作流
 */
export const getWorkflowPublish = (id: number): Promise<ApiResult<WorkflowPublish | null>> => {
  return api.get(`/api/workflows/${id}/publish`);
};

export const publishWorkflow = (id: number): Promise<ApiResult<WorkflowPublish>> => {
  return api.post(`/api/workflows/${id}/publish`);
};

export const unpublishWorkflow = (id: number): Promise<ApiResult<WorkflowPublish | null>> => {
  return api.delete(`/api/workflows/${id}/publish`);
};

export const getPublishedWorkflow = (shareKey: string): Promise<ApiResult<PublishedWorkflowInfo>> => {
  return api.get(`/api/published-workflows/${shareKey}`);
};

export const executePublishedWorkflow = (
  shareKey: string,
  inputData: string
): Promise<ApiResult<ExecutionResponse>> => {
  return api.post(`/api/published-workflows/${shareKey}/execute`, { inputData });
};

export const listWorkflowTestCases = (workflowId: number): Promise<ApiResult<WorkflowTestCase[]>> => {
  return api.get(`/api/workflows/${workflowId}/harness/test-cases`);
};

export const createWorkflowTestCase = (
  workflowId: number,
  data: WorkflowTestCaseRequest
): Promise<ApiResult<WorkflowTestCase>> => {
  return api.post(`/api/workflows/${workflowId}/harness/test-cases`, data);
};

export const updateWorkflowTestCase = (
  workflowId: number,
  caseId: number,
  data: WorkflowTestCaseRequest
): Promise<ApiResult<WorkflowTestCase>> => {
  return api.put(`/api/workflows/${workflowId}/harness/test-cases/${caseId}`, data);
};

export const deleteWorkflowTestCase = (
  workflowId: number,
  caseId: number
): Promise<ApiResult<void>> => {
  return api.delete(`/api/workflows/${workflowId}/harness/test-cases/${caseId}`);
};

export const runWorkflowTestCases = (workflowId: number): Promise<ApiResult<WorkflowTestRun>> => {
  return api.post(`/api/workflows/${workflowId}/harness/test-runs`);
};

export const listWorkflowTestRuns = (workflowId: number): Promise<ApiResult<WorkflowTestRun[]>> => {
  return api.get(`/api/workflows/${workflowId}/harness/test-runs`);
};

export const executeWorkflow = (id: number, inputData: string): Promise<ApiResult<WorkflowExecutionPayload>> => {
  return api.post(`/api/workflows/${id}/execute`, { inputData });
};

export interface ExecutionEvent {
  eventType: string;
  nodeId?: string;
  nodeName?: string;
  status?: string;
  message?: string;
  data?: unknown;
  timestamp?: number;
}

interface StreamTicketResponse {
  ticket: string;
  expiresInSeconds: number;
}

const createWorkflowStreamTicket = (id: number): Promise<ApiResult<StreamTicketResponse>> => {
  return api.post(`/api/workflows/${id}/execute/stream-ticket`);
};

const getResponseStatus = (error: unknown) => {
  return (error as { response?: { status?: number } }).response?.status;
};

const getResponseMessage = (error: unknown, fallback: string) => {
  const responseMessage = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  if (responseMessage) {
    return responseMessage;
  }
  return error instanceof Error ? error.message : fallback;
};

export const executeWorkflowStream = async (
  id: number, 
  inputData: string, 
  onEvent: (event: ExecutionEvent) => void,
  onComplete: () => void,
  onError: (error: Error) => void
) => {
  let ticket: string | undefined;
  try {
    const ticketResult = await createWorkflowStreamTicket(id);
    if (ticketResult.code !== 200) {
      onError(new Error(ticketResult.message || '创建实时执行票据失败'));
      return null;
    }
    ticket = ticketResult.data?.ticket;
  } catch (error) {
    if (getResponseStatus(error) === 401) {
      clearStoredAuth();
      window.location.href = '/login';
      onError(new Error('认证失败,请重新登录'));
      return null;
    }
    onError(new Error(getResponseMessage(error, '创建实时执行票据失败')));
    return null;
  }

  if (!ticket) {
    onError(new Error('创建实时执行票据失败'));
    return null;
  }
  
  const url = buildBackendUrl(
    `/api/workflows/${id}/execute/stream?inputData=${encodeURIComponent(inputData)}&ticket=${encodeURIComponent(ticket)}`
  );
  
  const eventSource = new EventSource(url);
  
  let hasReceivedData = false;
  
  eventSource.addEventListener('WORKFLOW_START', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_START', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_SUCCESS', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_PROGRESS', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });

  eventSource.addEventListener('NODE_RETRY', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_ERROR', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('WORKFLOW_COMPLETE', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
    eventSource.close();
    onComplete();
  });
  
  eventSource.addEventListener('ERROR', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
    eventSource.close();
    onError(new Error(event.message || '执行失败'));
  });
  
  eventSource.onerror = () => {
    eventSource.close();
    
    if (!hasReceivedData) {
      onError(new Error('实时执行连接失败,请稍后重试'));
    } else {
      onError(new Error('连接中断'));
    }
  };
  
  return eventSource;
};
