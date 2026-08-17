import { request } from '../../shared/api/httpClient';

export interface ServerMessage { id:string;sequence:number;role:'user'|'assistant'|'system';type:string;content:string;contentJson?:string;invocationId?:string;createdAt:string }
export interface ServerConversation { id:string;agentId:string;sessionId:string;title:string;status:string;currentInvocationId?:string;checkpointId?:string;checkpointRevision?:number;createdAt:string;updatedAt:string;messageCount:number;activeToolName?:string;activeToolStartedAt?:string;activeToolStatus?:string;messages:ServerMessage[] }
export const queryConversations=()=>request<ServerConversation[]>('/api/v1/conversations?limit=100');
export const queryConversation=(id:string)=>request<ServerConversation>(`/api/v1/conversations/${encodeURIComponent(id)}`);
export const deleteConversation=(id:string)=>request<boolean>(`/api/v1/conversations/${encodeURIComponent(id)}`,{method:'DELETE'});
