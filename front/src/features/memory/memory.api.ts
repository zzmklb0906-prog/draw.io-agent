import { request } from '../../shared/api/httpClient';
import type { AgentMemory, MemoryEvidence } from './memory.types';
export const queryMemories=(userId:string,projectId='')=>request<AgentMemory[]>(`/api/v1/memories?userId=${encodeURIComponent(userId)}&projectId=${encodeURIComponent(projectId)}&limit=100`);
export const confirmMemory=(id:string)=>request<AgentMemory>(`/api/v1/memories/${id}/confirm`,{method:'POST'});
export const deleteMemory=(id:string)=>request<{deleted:boolean}>(`/api/v1/memories/${id}`,{method:'DELETE'});
export const createMemory=(memory:{userId:string;projectId?:string;memoryType:string;content:string;structuredData?:string;importance:number;confidence:number;confirmed:boolean})=>request<AgentMemory>('/api/v1/memories',{method:'POST',body:JSON.stringify(memory)});
export const updateMemory=(memory:AgentMemory)=>request<AgentMemory>(`/api/v1/memories/${memory.memoryId}`,{method:'PUT',body:JSON.stringify({content:memory.content,structuredData:memory.structuredData,importance:memory.importance,confirmed:memory.confirmed})});
export const queryMemoryEvidence=(id:string)=>request<MemoryEvidence[]>(`/api/v1/memories/${id}/evidence`);
export const consolidateMemories=(projectId='')=>request<{scanned:number;merged:number;remaining:number;conflictCount:number;conflicts:Array<Record<string,string>>;strategy:string}>(`/api/v1/memories/consolidate?projectId=${encodeURIComponent(projectId)}`,{method:'POST'});
export const queryMemoryRetrieve = (userId: string, projectId = '', query: string, limit = 8) =>
  request<AgentMemory[]>(
    `/api/v1/memories/retrieve?userId=${encodeURIComponent(userId)}&projectId=${encodeURIComponent(projectId)}&query=${encodeURIComponent(query)}&limit=${limit}`
  );
