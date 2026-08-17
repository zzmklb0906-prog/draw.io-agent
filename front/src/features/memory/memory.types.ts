export interface AgentMemory { memoryId:string;userId:string;projectId?:string;memoryType:string;content:string;structuredData?:string;importance:number;confidence:number;confirmed:boolean;sourceSessionId?:string;sourceEventId?:string;createdAt:number;updatedAt:number;expiresAt?:number; }
export interface MemoryEvidence { memoryId:string;evidenceType:string;evidenceId:string;createdAt:number; }
