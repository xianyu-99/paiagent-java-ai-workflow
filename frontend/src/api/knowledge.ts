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

export interface KnowledgeReindexResult {
  knowledgeBaseId: number;
  chunkCount: number;
  embeddingProvider: string;
  embeddingModel: string;
  embeddingDimension: number;
}

export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  fileName: string;
  contentType?: string;
  parserType?: string;
  chunkCount: number;
  createdAt?: string;
}

export interface KnowledgeChunk {
  id: number;
  documentId: number;
  chunkIndex: number;
  content: string;
  sourceName?: string;
  contentType?: string;
  sectionTitle?: string;
  pageNumber?: number;
  startOffset?: number;
  endOffset?: number;
  tokenCount?: number;
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

export const uploadKnowledgeFile = (
  knowledgeBaseId: number,
  file: File
): Promise<ApiResult<KnowledgeDocument>> => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post(`/api/knowledge-bases/${knowledgeBaseId}/documents/file`, formData);
};

export const deleteKnowledgeBase = (knowledgeBaseId: number): Promise<ApiResult<void>> => {
  return api.delete(`/api/knowledge-bases/${knowledgeBaseId}`);
};

export const listKnowledgeDocuments = (
  knowledgeBaseId: number
): Promise<ApiResult<KnowledgeDocument[]>> => {
  return api.get(`/api/knowledge-bases/${knowledgeBaseId}/documents`);
};

export const listKnowledgeChunks = (
  knowledgeBaseId: number,
  documentId: number
): Promise<ApiResult<KnowledgeChunk[]>> => {
  return api.get(`/api/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks`);
};

export const rebuildKnowledgeBaseEmbeddings = (
  knowledgeBaseId: number
): Promise<ApiResult<KnowledgeReindexResult>> => {
  return api.post(`/api/knowledge-bases/${knowledgeBaseId}/reindex`);
};
