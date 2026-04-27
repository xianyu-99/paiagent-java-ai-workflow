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
    <div className={`relative px-4 py-3 rounded-lg border-2 bg-white shadow-sm min-w-[150px] ${selected ? 'border-blue-500' : 'border-orange-300'}`}>
      <Handle type="target" position={Position.Top} />
      <div className="text-center">
        <div className="text-lg">🔀</div>
        <div className="font-semibold text-gray-800">{label}</div>
        <div className="text-xs text-gray-500 mt-1">true / false</div>
      </div>
      <Handle
        id="true"
        type="source"
        position={Position.Right}
        style={{ top: '38%', background: '#16a34a' }}
      />
      <Handle
        id="false"
        type="source"
        position={Position.Right}
        style={{ top: '72%', background: '#dc2626' }}
      />
      <div className="absolute -right-12 top-[28%] text-xs text-green-600 bg-white px-1">true</div>
      <div className="absolute -right-12 top-[62%] text-xs text-red-600 bg-white px-1">false</div>
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

  // 当 store 中的 nodes/edges 变化时，同步更新到本地状态
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

  // 同步到 store
  const handleNodesChange: OnNodesChange = useCallback((changes) => {
    onNodesChange(changes);
    // 使用 setTimeout 确保状态更新后再同步
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

  // 处理拖拽放置
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
        x: flowPosition.x - 75,
        y: flowPosition.y - 25,
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
                leftReference: 'input-default.user_input',
                operator: 'contains',
                rightValue: '',
                caseSensitive: false,
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
