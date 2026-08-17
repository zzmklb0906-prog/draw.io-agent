import type { ChatMessage, WorkflowStatus } from '../chat/chat.types';

export interface ConversationSnapshot {
  id: string;
  title: string;
  agentId: string;
  sessionId: string;
  messages: ChatMessage[];
  messageCount?: number;
  currentXml: string;
  lastAiXml: string;
  diagramTitle: string;
  checkpointId?: string;
  checkpointRevision?: number;
  invocationId?: string;
  workflowStatus: WorkflowStatus;
  phase?: string;
  activeTool?: { name: string; startedAt: number; durationMs?: number; status: 'RUNNING' | 'SUCCESS' | 'FAILED' };
  createdAt: number;
  updatedAt: number;
}

export type ConversationDraft = Omit<ConversationSnapshot, 'id' | 'createdAt' | 'updatedAt' | 'title'> & {
  title?: string;
};
