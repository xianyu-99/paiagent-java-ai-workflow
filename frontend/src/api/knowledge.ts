import api from '../utils/request';
import type { ApiResult } from './workflow';

export interface KnowledgeBase {
  id: number;
  name: string;
  description?: string;
  ownerId?: number;
  documentCount?: number;
  chunkCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  fileName: string;
  chunkCount: number;
  createdAt?: string;
}

export const listKnowledgeBases = (): Promise<ApiResult<KnowledgeBase[]>> => {
  return api.get('/api/knowledge-bases');
};

export const createKnowledgeBase = (data: {
  name: string;
  description?: string;
}): Promise<ApiResult<KnowledgeBase>> => {
  return api.post('/api/knowledge-bases', data);
};

export const uploadKnowledgeDocument = (
  knowledgeBaseId: number,
  data: {
    fileName?: string;
    content: string;
  }
): Promise<ApiResult<KnowledgeDocument>> => {
  return api.post(`/api/knowledge-bases/${knowledgeBaseId}/documents`, data);
};

export const listKnowledgeDocuments = (
  knowledgeBaseId: number
): Promise<ApiResult<KnowledgeDocument[]>> => {
  return api.get(`/api/knowledge-bases/${knowledgeBaseId}/documents`);
};
