import { create } from 'zustand';
import type { ApprovalRequest, ChatMessage, StreamPhase, ToolApprovalRequest, ToolRun, WorkflowStatus } from './chat.types';

const makeId = () => crypto.randomUUID();

interface ChatState {
  agentId: string;
  sessionId: string;
  conversationId: string;
  messages: ChatMessage[];
  phase: StreamPhase;
  statusText: string;
  nodeCount: number;
  edgeCount: number;
  activeRequestId: string | null;
  checkpointId: string;
  checkpointRevision: number;
  workflowStatus: WorkflowStatus;
  toolRuns: ToolRun[];
  setAgent: (agentId: string) => void;
  setSession: (sessionId: string, conversationId?: string) => void;
  resetSession: () => void;
  restoreConversation: (agentId: string, sessionId: string, messages: ChatMessage[], workflowStatus?: WorkflowStatus) => void;
  addMessage: (role: ChatMessage['role'], content: string) => void;
  addApproval: (approval: ApprovalRequest) => void;
  addToolApproval: (approval: ToolApprovalRequest) => void;
  appendAssistant: (content: string) => void;
  beginRequest: (requestId: string) => void;
  setProgress: (phase: StreamPhase, statusText?: string) => void;
  addNode: () => void;
  addEdge: () => void;
  finishRequest: (phase?: 'done' | 'error') => void;
  setCheckpoint: (checkpointId: string, revision: number, status?: WorkflowStatus) => void;
  updateTool: (tool: ToolRun) => void;
  setApprovalDecision: (checkpointId: string, decisionStatus: NonNullable<ApprovalRequest['decisionStatus']>) => void;
  setToolApprovalDecision: (callId:string,decisionStatus:NonNullable<ToolApprovalRequest['decisionStatus']>)=>void;
}

export const useChatStore = create<ChatState>((set) => ({
  agentId: '',
  sessionId: '',
  conversationId: '',
  messages: [],
  phase: 'idle',
  statusText: '',
  nodeCount: 0,
  edgeCount: 0,
  activeRequestId: null,
  checkpointId: '',
  checkpointRevision: 0,
  workflowStatus: 'IDLE',
  toolRuns: [],
  setAgent: (agentId) => set({ agentId }),
  setSession: (sessionId, conversationId = '') => set({ sessionId, conversationId }),
  resetSession: () =>
    set({ sessionId: '', conversationId: '', messages: [], phase: 'idle', statusText: '', nodeCount: 0, edgeCount: 0, checkpointId: '', checkpointRevision: 0, workflowStatus: 'IDLE', toolRuns: [] }),
  restoreConversation: (agentId, sessionId, messages, workflowStatus = 'IDLE') =>
    set({ agentId, sessionId, messages, phase: 'idle', statusText: '', nodeCount: 0, edgeCount: 0, activeRequestId: null, workflowStatus, toolRuns: [] }),
  addMessage: (role, content) =>
    set((state) => ({
      messages: [...state.messages, { id: makeId(), role, content, createdAt: Date.now() }],
    })),
  addApproval: (approval) =>
    set((state) => ({
      checkpointId: approval.checkpointId,
      checkpointRevision: approval.revision,
      workflowStatus: 'WAITING_APPROVAL',
      messages: [
        ...state.messages,
        {
          id: makeId(),
          role: 'assistant',
          content: `### ${approval.title}\n\n${approval.rewrittenPrompt}`,
          createdAt: Date.now(),
          approval: { ...approval, decisionStatus: 'PENDING' },
        },
      ],
    })),
  addToolApproval:(approval)=>set((state)=>({checkpointId:approval.checkpointId,checkpointRevision:approval.revision,workflowStatus:'WAITING_TOOL_APPROVAL',messages:[...state.messages,{id:makeId(),role:'assistant',content:'高风险工具需要你的批准。',createdAt:Date.now(),toolApproval:{...approval,decisionStatus:'PENDING'}}]})),
  appendAssistant: (content) =>
    set((state) => {
      const messages = [...state.messages];
      const last = messages.at(-1);
      if (last?.role === 'assistant') last.content += content;
      else messages.push({ id: makeId(), role: 'assistant', content, createdAt: Date.now() });
      return { messages };
    }),
  beginRequest: (activeRequestId) =>
    set({ activeRequestId, phase: 'thinking', workflowStatus: 'RUNNING', statusText: '准备执行', nodeCount: 0, edgeCount: 0, toolRuns: [] }),
  setProgress: (phase, statusText = '') => set({ phase, statusText }),
  addNode: () => set((state) => ({ nodeCount: state.nodeCount + 1 })),
  addEdge: () => set((state) => ({ edgeCount: state.edgeCount + 1 })),
  finishRequest: (phase = 'done') => set((state) => ({
    activeRequestId: null,
    phase,
    workflowStatus: state.workflowStatus === 'WAITING_APPROVAL' || state.workflowStatus === 'WAITING_TOOL_APPROVAL' || state.workflowStatus === 'PAUSED'
      ? state.workflowStatus
      : phase === 'done' ? 'COMPLETED' : 'FAILED',
  })),
  setCheckpoint: (checkpointId, checkpointRevision, status) => set((state) => ({
    checkpointId,
    checkpointRevision,
    ...(status ? { workflowStatus: status } : {}),
    messages: state.messages.map((message) => message.approval?.checkpointId === checkpointId
      ? { ...message, approval: { ...message.approval, revision: checkpointRevision } }
      : message),
  })),
  updateTool: (tool) => set((state) => ({ toolRuns: [...state.toolRuns.filter((item) => item.callId !== tool.callId), tool] })),
  setApprovalDecision: (checkpointId, decisionStatus) => set((state) => ({
    messages: state.messages.map((message) => message.approval?.checkpointId === checkpointId
      ? { ...message, approval: { ...message.approval, decisionStatus } }
      : message),
  })),
  setToolApprovalDecision:(callId,decisionStatus)=>set((state)=>({messages:state.messages.map((message)=>message.toolApproval?.callId===callId?{...message,toolApproval:{...message.toolApproval,decisionStatus}}:message)})),
}));
