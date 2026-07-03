import { create } from 'zustand';
import { Node, Edge } from '@xyflow/react';
import { createDefaultWorkflowEdges, createDefaultWorkflowNodes, normalizeWorkflowNodes } from '../utils/workflowNode';

interface WorkflowState {
  nodes: Node[];
  edges: Edge[];
  selectedNode: Node | null;
  currentWorkflowId: number | null;
  setNodes: (nodes: Node[]) => void;
  setEdges: (edges: Edge[]) => void;
  setSelectedNode: (node: Node | null) => void;
  setCurrentWorkflowId: (id: number | null) => void;
  addNode: (node: Node) => void;
  updateNode: (id: string, data: Record<string, unknown>) => void;
  deleteNode: (id: string) => void;
  clear: () => void;
}

const defaultNodes: Node[] = createDefaultWorkflowNodes();
const defaultEdges: Edge[] = createDefaultWorkflowEdges();

export const useWorkflowStore = create<WorkflowState>((set) => ({
  nodes: defaultNodes,
  edges: defaultEdges,
  selectedNode: null,
  currentWorkflowId: null,
  
  setNodes: (nodes) => set({ nodes: normalizeWorkflowNodes(nodes) }),
  
  setEdges: (edges) => set({ edges }),
  
  setSelectedNode: (node) => set({ selectedNode: node }),
  
  setCurrentWorkflowId: (id) => set({ currentWorkflowId: id }),
  
  addNode: (node) => set((state) => ({
    nodes: [...state.nodes, normalizeWorkflowNodes([node])[0]]
  })),
  
  updateNode: (id, data) => set((state) => {
    const update = (node: Node) => ({ ...node, data: { ...node.data, ...data } });
    return {
      nodes: state.nodes.map((node) => (node.id === id ? update(node) : node)),
      selectedNode: state.selectedNode?.id === id ? update(state.selectedNode) : state.selectedNode,
    };
  }),
  
  deleteNode: (id) => set((state) => ({
    nodes: state.nodes.filter((node) => node.id !== id),
    edges: state.edges.filter((edge) => edge.source !== id && edge.target !== id)
  })),
  
  clear: () => set({
    nodes: createDefaultWorkflowNodes(),
    edges: createDefaultWorkflowEdges(),
    selectedNode: null,
    currentWorkflowId: null
  })
}));
