import { request } from '../../shared/api/httpClient';
import type { InvocationDetail, InvocationItem, MonitorSummary, WorkflowDetail } from './monitor.types';
export const queryMonitorSummary = (sessionId?:string) => request<MonitorSummary>(`/api/v1/monitor/summary${sessionId?`?sessionId=${encodeURIComponent(sessionId)}`:''}`);
export const queryInvocations = () => request<InvocationItem[]>('/api/v1/monitor/invocations');
export const queryInvocation = (id: string) => request<InvocationDetail>(`/api/v1/monitor/invocations/${encodeURIComponent(id)}`);
export const querySessionInvocations = (sessionId:string) => request<InvocationItem[]>(`/api/v1/monitor/sessions/${encodeURIComponent(sessionId)}/invocations`);
export const queryWorkflowDetail = (taskId:string) => request<WorkflowDetail>(`/api/v1/monitor/workflows/${encodeURIComponent(taskId)}`);
