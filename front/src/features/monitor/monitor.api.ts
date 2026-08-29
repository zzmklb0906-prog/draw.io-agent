import { request } from '../../shared/api/httpClient';
import type { InvocationDetail, InvocationItem, MonitorSummary, WorkflowDetail } from './monitor.types';
export type CapabilityFeedbackJudgment = 'NO_IMPACT' | 'WRONG_SELECTION';

export const queryMonitorSummary = (sessionId?: string, hours?: number) => {
  const params = new URLSearchParams();
  if (sessionId) params.append('sessionId', sessionId);
  if (hours !== undefined && hours !== null) params.append('hours', String(hours));
  const qs = params.toString();
  return request<MonitorSummary>(`/api/v1/monitor/summary${qs ? `?${qs}` : ''}`);
};
export const queryInvocations = () => request<InvocationItem[]>('/api/v1/monitor/invocations');
export const queryInvocation = (id: string) => request<InvocationDetail>(`/api/v1/monitor/invocations/${encodeURIComponent(id)}`);
export const querySessionInvocations = (sessionId:string) => request<InvocationItem[]>(`/api/v1/monitor/sessions/${encodeURIComponent(sessionId)}/invocations`);
export const queryWorkflowDetail = (taskId:string) => request<WorkflowDetail>(`/api/v1/monitor/workflows/${encodeURIComponent(taskId)}`);
export const submitCapabilityFeedback = (invocationId: string, body: { searchId: string; capabilityId: string; judgment: CapabilityFeedbackJudgment; note?: string }) =>
  request<boolean>(`/api/v1/monitor/invocations/${encodeURIComponent(invocationId)}/capability-feedback`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
