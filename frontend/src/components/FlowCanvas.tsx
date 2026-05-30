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

const ConditionNode = ({ data, selected }: NodeProps) => {
  const label = typeof data?.label === 'string' ? data.label : '条件分支';

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
  condition: ConditionNode,
};

const FlowCanvasContent = ({ onNodeClick }: FlowCanvasProps) => {
  const { nodes: storeNodes, edges: storeEdges, setNodes: setStoreNodes, setEdges: setStoreEdges } = useWorkflowStore();
  const { screenToFlowPosition } = useReactFlow();

  const [nodes, setNodes, onNodesChange] = useNodesState(storeNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(storeEdges);

  useEffect(() => {
    console.log('Store nodes changed:', storeNodes);
    setNodes(storeNodes);
  }, [storeNodes, setNodes]);

  useEffect(() => {
    console.log('Store edges changed:', storeEdges);
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
    console.log('Connection created:', connection);
    setEdges((eds) => {
      const newEdge = {
        ...connection,
        markerEnd: {
          type: MarkerType.ArrowClosed,
          width: 20,
          height: 20,
        },
      };
      console.log('New edge:', newEdge);
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
      const position = {
        x: flowPosition.x - (type === 'condition' ? 89 : 75),
        y: flowPosition.y - (type === 'condition' ? 89 : 25),
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
