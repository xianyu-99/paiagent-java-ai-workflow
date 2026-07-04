import React, { useEffect } from 'react';
import { Node } from '@xyflow/react';
import { InputConfig } from './InputConfig';
import { OutputConfig } from './OutputConfig';
import { LlmConfig } from './LlmConfig';
import { AgentConfig } from './AgentConfig';
import { TtsConfig } from './TtsConfig';
import { RagConfig } from './RagConfig';
import { ConditionConfig } from './ConditionConfig';
import { MediaConfig } from './MediaConfig';
import { QueryEnhancementConfig } from './QueryEnhancementConfig';
import { isLlmNodeType } from '../../utils/provider';
import { DraftSaver } from './types';

export interface NodeConfigPanelProps {
  node: Node;
  onSave: () => Promise<void>;
  getReferenceableParams: () => { label: string; value: string }[];
  registerDraftSaver?: (saver: DraftSaver | null) => void;
}

export const NodeConfigPanel: React.FC<NodeConfigPanelProps> = ({ node, onSave, getReferenceableParams, registerDraftSaver }) => {
  const nodeType = String(node.data?.type || '');

  useEffect(() => {
    return () => registerDraftSaver?.(null);
  }, [node.id, registerDraftSaver]);

  if (nodeType === 'input') {
    return <InputConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }
  
  if (nodeType === 'output') {
    return <OutputConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'agent') {
    return <AgentConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (isLlmNodeType(nodeType)) {
    return <LlmConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'tts') {
    return <TtsConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'rag') {
    return <RagConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'hyde' || nodeType === 'query_expansion') {
    return <QueryEnhancementConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'condition') {
    return <ConditionConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  if (nodeType === 'media') {
    return <MediaConfig node={node} onSave={onSave} getReferenceableParams={getReferenceableParams} registerDraftSaver={registerDraftSaver} />;
  }

  return (
    <div className="mt-4 p-4 text-center text-gray-500 bg-gray-50 rounded">
      未知的节点类型: {nodeType}
    </div>
  );
};
