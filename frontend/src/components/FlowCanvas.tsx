import {
  Background,
  Connection,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Node,
  NodeProps,
  OnConnect,
  OnEdgesChange,
  OnNodesChange,
  Position,
  ReactFlow,
  ReactFlowProvider,
  addEdge,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useCallback, useEffect } from 'react';
import { useWorkflowStore } from '../store/workflowStore';

interface FlowCanvasProps {
  onNodeClick: (node: Node) => void;
}

const DEFAULT_NODE_WIDTH = 190;
const DEFAULT_NODE_HEIGHT = 72;
const CONDITION_NODE_SIZE = 178;
const DEFAULT_NODE_STYLE = {
  width: DEFAULT_NODE_WIDTH,
  padding: 0,
  border: 'none',
  background: 'transparent',
};

const withNodePresentation = (node: Node): Node => {
  if (node.type === 'condition') {
    return node;
  }

  return {
    ...node,
    style: {
      ...node.style,
      ...DEFAULT_NODE_STYLE,
    },
  };
};

const withNodesPresentation = (nodes: Node[]) => nodes.map(withNodePresentation);

const getNodeLabel = (data: NodeProps['data'], fallback: string) => {
  return typeof data?.label === 'string' && data.label.trim() ? data.label : fallback;
};

const getNodeType = (data: NodeProps['data']) => {
  return typeof data?.type === 'string' ? data.type : 'node';
};

const getNodeAccentClass = (type: string) => {
  switch (type) {
    case 'input':
      return 'border-emerald-400 bg-emerald-50 text-emerald-700';
    case 'output':
      return 'border-sky-400 bg-sky-50 text-sky-700';
    case 'llm':
    case 'agent':
      return 'border-violet-400 bg-violet-50 text-violet-700';
    case 'rag':
      return 'border-amber-400 bg-amber-50 text-amber-700';
    case 'tts':
    case 'media':
      return 'border-rose-400 bg-rose-50 text-rose-700';
    default:
      return 'border-slate-300 bg-slate-50 text-slate-600';
  }
};

const DefaultWorkflowNode = ({ data, selected }: NodeProps) => {
  const type = getNodeType(data);
  const label = getNodeLabel(data, type);
  const canReceiveInput = type !== 'input';
  const canEmitOutput = type !== 'output';

  return (
    <div
      className={`relative flex min-h-[72px] w-[190px] items-center rounded-md border-2 bg-white px-4 py-3 shadow-sm transition-shadow ${
        selected ? 'border-blue-500 shadow-blue-100' : 'border-gray-200'
      }`}
    >
      {canReceiveInput && (
        <Handle
          type="target"
          position={Position.Left}
          style={{ left: -6, background: '#111827' }}
        />
      )}
      <div className="min-w-0">
        <div
          className={`mb-1 inline-flex max-w-full rounded px-1.5 py-0.5 text-[11px] font-semibold uppercase leading-none ${getNodeAccentClass(type)}`}
        >
          {type}
        </div>
        <div className="text-sm font-semibold leading-snug text-gray-900 break-words">
          {label}
        </div>
      </div>
      {canEmitOutput && (
        <Handle
          type="source"
          position={Position.Right}
          style={{ right: -6, background: '#2563eb' }}
        />
      )}
    </div>
  );
};

const ConditionNode = ({ data, selected }: NodeProps) => {
  const label = getNodeLabel(data, '条件分支');

  return (
    <div className="relative w-[178px] h-[178px] flex items-center justify-center overflow-visible">
      <div
        className={`absolute left-1/2 top-1/2 h-[122px] w-[122px] -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[6px] border-2 bg-white shadow-sm ${
          selected ? 'border-blue-500 shadow-blue-100' : 'border-orange-400'
        }`}
      />
      <Handle
        type="target"
        position={Position.Top}
        style={{ top: 5, background: '#111827', zIndex: 20 }}
      />
      <div className="relative z-10 w-[112px] text-center">
        <div className="text-xs font-semibold uppercase tracking-wide text-orange-500">if</div>
        <div className="text-base font-semibold text-gray-900 leading-snug break-words">{label}</div>
        <div className="text-xs text-gray-500 mt-1">true / false</div>
      </div>
      <Handle
        id="true"
        type="source"
        position={Position.Right}
        style={{ top: '50%', right: 5, background: '#16a34a', zIndex: 20 }}
      />
      <Handle
        id="false"
        type="source"
        position={Position.Bottom}
        style={{ bottom: 5, background: '#dc2626', zIndex: 20 }}
      />
      <div className="absolute -right-9 top-1/2 -translate-y-1/2 text-xs text-green-600 bg-white px-1">true</div>
      <div className="absolute left-1/2 -translate-x-1/2 -bottom-1 text-xs text-red-600 bg-white px-1">false</div>
    </div>
  );
};

const nodeTypes = {
  default: DefaultWorkflowNode,
  condition: ConditionNode,
};

const FlowCanvasContent = ({ onNodeClick }: FlowCanvasProps) => {
  const { nodes: storeNodes, edges: storeEdges, setNodes: setStoreNodes, setEdges: setStoreEdges } = useWorkflowStore();
  const { screenToFlowPosition } = useReactFlow();

  const [nodes, setNodes, onNodesChange] = useNodesState(withNodesPresentation(storeNodes));
  const [edges, setEdges, onEdgesChange] = useEdgesState(storeEdges);

  useEffect(() => {
    setNodes(withNodesPresentation(storeNodes));
  }, [storeNodes, setNodes]);

  useEffect(() => {
    const edgesWithMarkers = storeEdges.map(edge => ({
      ...edge,
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 20,
        height: 20,
      },
    }));
    setEdges(edgesWithMarkers);
  }, [storeEdges, setEdges]);

  const handleNodesChange: OnNodesChange = useCallback((changes) => {
    const removedNodeIds = changes
      .filter((change) => change.type === 'remove')
      .map((change) => change.id);
    const selectedNode = useWorkflowStore.getState().selectedNode;
    if (selectedNode && removedNodeIds.includes(selectedNode.id)) {
      useWorkflowStore.getState().setSelectedNode(null);
    }

    onNodesChange(changes);
    setTimeout(() => {
      setNodes((currentNodes) => {
        setStoreNodes(currentNodes);
        return currentNodes;
      });
    }, 0);
  }, [onNodesChange, setNodes, setStoreNodes]);

  const handleEdgesChange: OnEdgesChange = useCallback((changes) => {
    onEdgesChange(changes);
    setTimeout(() => {
      setEdges((currentEdges) => {
        setStoreEdges(currentEdges);
        return currentEdges;
      });
    }, 0);
  }, [onEdgesChange, setEdges, setStoreEdges]);

  const handleConnect: OnConnect = useCallback((connection: Connection) => {
    setEdges((eds) => {
      const newEdge = {
        ...connection,
        markerEnd: {
          type: MarkerType.ArrowClosed,
          width: 20,
          height: 20,
        },
      };
      const updatedEdges = addEdge(newEdge, eds);
      setStoreEdges(updatedEdges);
      return updatedEdges;
    });
  }, [setEdges, setStoreEdges]);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/reactflow-type');
      const label = event.dataTransfer.getData('application/reactflow-label');

      if (!type) return;

      const flowPosition = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });
      const nodeWidth = type === 'condition' ? CONDITION_NODE_SIZE : DEFAULT_NODE_WIDTH;
      const nodeHeight = type === 'condition' ? CONDITION_NODE_SIZE : DEFAULT_NODE_HEIGHT;
      const position = {
        x: flowPosition.x - nodeWidth / 2,
        y: flowPosition.y - nodeHeight / 2,
      };

      const newNode: Node = {
        id: `${type}-${Date.now()}`,
        type: type === 'condition' ? 'condition' : 'default',
        position,
        data: {
          label: label || type,
          type,
          ...(type === 'condition'
            ? {
                leftType: 'reference',
                leftReference: 'input-default.input',
                operator: 'contains',
                rightValue: '',
                caseSensitive: false,
              }
            : {}),
          ...(type === 'rag'
            ? {
                topK: 3,
                minScore: 0,
                prompt: '',
                inputParams: [{ name: 'question', type: 'reference', referenceNode: 'input-default.input' }],
                outputParams: [{ name: 'output', type: 'string' }],
              }
            : {}),
        },
      };

      setNodes((nds) => {
        const updatedNodes = nds.concat(newNode);
        setStoreNodes(updatedNodes);
        return updatedNodes;
      });
    },
    [screenToFlowPosition, setNodes, setStoreNodes]
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onNodeClick(node);
    },
    [onNodeClick]
  );

  return (
    <div className="h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={handleConnect}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onNodeClick={handleNodeClick}
        nodeTypes={nodeTypes}
        defaultEdgeOptions={{
          markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 20,
            height: 20,
          },
        }}
        fitView
      >
        <Background />
        <Controls />
        <MiniMap />
      </ReactFlow>
    </div>
  );
};

const FlowCanvas = ({ onNodeClick }: FlowCanvasProps) => {
  return (
    <ReactFlowProvider>
      <FlowCanvasContent onNodeClick={onNodeClick} />
    </ReactFlowProvider>
  );
};

export default FlowCanvas;
