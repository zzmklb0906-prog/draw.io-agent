export type StreamPhase =
  | 'idle'
  | 'thinking'
  | 'analyzing'
  | 'drawing'
  | 'generating'
  | 'reviewing'
  | 'done'
  | 'error';

export type WorkflowStatus = 'IDLE' | 'RUNNING' | 'PAUSED' | 'WAITING_APPROVAL' | 'WAITING_TOOL_APPROVAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type StreamChunk =
  | { type: 'token'; content: string }
  | { type: 'status'; content: string }
  | { type: 'tool'; callId: string; name: string; status: 'RUNNING' | 'SUCCESS' | 'FAILED'; startedAt: number; durationMs?: number }
  | { type: 'checkpoint'; checkpointId: string; revision: number; status: string }
  | ({ type: 'tool_approval' } & ToolApprovalRequest)
  | { type: 'user'; content: string }
  | ({ type: 'approval' } & ApprovalRequest)
  | { type: 'drawio_node'; id: string; label?: string; xml: string }
  | {
      type: 'drawio_edge';
      id: string;
      label?: string;
      source: string;
      target: string;
      xml: string;
    }
  | { type: 'drawio_done'; content: string }
  | { type: 'drawio'; content: string }
  | { type: 'ppt_raw'; raw: string }
  | { type: 'done' }
  | { type: 'error'; content: string };

export interface StreamEnvelope {
  phase: Exclude<StreamPhase, 'idle'>;
  chunk: StreamChunk;
}

export interface ChatRequest {
  agentId: string;
  userId: string;
  sessionId: string;
  conversationId?: string;
  message: string;
  idempotencyKey?: string;
  customBaseUrl?: string;
  customApiKey?: string;
  customCompletionsPath?: string;
  customModel?: string;
  checkpointId?: string;
  checkpointRevision?: number;
  checkpointDecision?: 'APPROVE' | 'REVISE' | 'CONTINUE' | 'TOOL_APPROVE' | 'TOOL_DENY';
  toolConfirmationCallId?: string;
  toolConfirmed?: boolean;
  toolConfirmationPayload?: Record<string, unknown>;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: number;
  approval?: ApprovalRequest;
  toolApproval?: ToolApprovalRequest;
}

export interface ToolRun { callId: string; name: string; status: 'RUNNING' | 'SUCCESS' | 'FAILED'; startedAt: number; durationMs?: number }

export interface ApprovalRequest {
  title: string;
  rewrittenPrompt: string;
  diagramType: string;
  scope: string[];
  assumptions: string[];
  questions: string[];
  checkpointId: string;
  revision: number;
  checkpointStatus?: string;
  decisionStatus?: 'PENDING' | 'APPROVED' | 'REVISED' | 'COMPLETED';
}

export interface ToolApprovalRequest { callId:string;checkpointId:string;revision:number;details:Record<string,unknown>;decisionStatus?:'PENDING'|'APPROVED'|'DENIED'|'HANDLED' }
