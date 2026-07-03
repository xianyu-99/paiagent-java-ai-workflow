import { Node } from '@xyflow/react';

export interface NodeConfigProps {
  node: Node;
  onSave: () => Promise<void>;
  getReferenceableParams: () => { label: string; value: string }[];
  registerDraftSaver?: (saver: (() => void) | null) => void;
}

export interface OutputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

export interface LlmInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

export interface LlmOutputParam {
  name: string;
  type: string;
  description?: string;
}

export interface TtsInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

export interface TtsOutputParam {
  name: string;
  value: string;
}

export interface ConditionConfig {
  leftType: 'input' | 'reference';
  leftValue: string;
  leftReference?: string;
  operator: string;
  rightValue: string;
  caseSensitive: boolean;
}

export interface RagConfig {
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
