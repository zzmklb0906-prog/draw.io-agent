import { request } from '../../shared/api/httpClient';
import type { WorkflowStatus } from './chat.types';

interface CreateSessionResponse {
  sessionId: string;
  conversationId: string;
}

export interface WorkflowCheckpoint {
  checkpointId: string;
  revision: number;
  status: WorkflowStatus;
}

export const createSession = (agentId: string, userId: string, idempotencyKey = crypto.randomUUID()) =>
  request<CreateSessionResponse>('/api/v1/create_session', {
    method: 'POST',
    body: JSON.stringify({ agentId, userId, idempotencyKey }),
  });

export const pauseWorkflow = (checkpointId: string) =>
  request<WorkflowCheckpoint>(`/api/v1/workflows/${encodeURIComponent(checkpointId)}/pause`, { method: 'POST' });

export const queryWorkflow = (checkpointId: string) =>
  request<WorkflowCheckpoint>(`/api/v1/workflows/${encodeURIComponent(checkpointId)}`);

export const cancelWorkflow = (checkpointId: string) =>
  request<WorkflowCheckpoint>(`/api/v1/workflows/${encodeURIComponent(checkpointId)}/cancel`, { method: 'POST' });
