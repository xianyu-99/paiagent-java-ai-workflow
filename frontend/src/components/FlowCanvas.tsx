import {
    Background,
    Connection,
    Controls,
    MarkerType,
    MiniMap,
    Node,
    OnConnect,
    OnEdgesChange,
    OnNodesChange,
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
        type: 'default',
        position,
        data: { label: label || type, type },
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
