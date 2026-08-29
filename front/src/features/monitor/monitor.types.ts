export interface MonitorSummary { windowHours: number; total: number; success: number; errors: number; active: number; successRate: number; averageDurationMs: number; p95DurationMs: number; inputTokens: number; outputTokens: number; totalTokens: number; estimatedCost: number; registeredTools: string[]; registeredCapabilities?: number }
export interface InvocationItem { invocationId: string; taskId: string; workflowName: string; sessionId: string; userId: string; rootAgent: string; status: string; startedAt: number; completedAt: number; durationMs: number; eventCount: number; agentCount: number; toolCount: number; compressionCount: number; inputTokens: number; outputTokens: number; totalTokens: number; tokensEstimated: boolean; error?: string }
export interface WorkflowTransition { sequence_no:number; from_status?:string; to_status:string; checkpoint_id?:string; invocation_id?:string; occurred_at:string }
export interface WorkflowArtifact { id:string; parent_artifact_id?:string; lineage_id:string; branch_name:string; artifact_type:string; name:string; version_no:number; status:string; size_bytes:number; created_at:string; invocation_id:string }
export interface WorkflowDetail { taskId:string; name:string; status:string; createdAt:number; completedAt:number; wallDurationMs:number; computeDurationMs:number; modelDurationMs:number; toolDurationMs:number; userWaitDurationMs:number; queueWaitDurationMs:number; inputTokens:number; outputTokens:number; estimatedCost:number; successes:number; errors:number; artifactCount:number; artifacts:WorkflowArtifact[]; finalResult:string; toolStats:{total:number;success:number;retries:number}; transitions:WorkflowTransition[]; invocations:InvocationItem[] }
export interface AgentExecution { name: string; startedAt: number; completedAt: number; durationMs: number; modelCalls: number; modelDurationMs: number; inputTokens: number; outputTokens: number; totalTokens: number; tokensEstimated: boolean }
export interface ModelExecution { id: string; agentName: string; modelName: string; status: string; startedAt: number; completedAt: number; durationMs: number; inputTokens: number; outputTokens: number; error?: string }
export interface ToolAttempt { attempt_no: number; status: string; started_at: string; completed_at?: string; duration_ms: number; result_summary?: string; error_message?: string }
export interface ToolExecution { callId: string; agentName: string; toolName: string; startedAt: number; completedAt: number; durationMs: number; status: string; arguments?: string; summary: string; retryCount?: number; error?: string; attempts?: ToolAttempt[] }
export interface CompressionRecord { beforeTokens: number; afterTokens: number; strategy: string; durationMs: number; timestamp: number }
export interface ModelDecision {
  agentName: string;
  model: string;
  reason: string;
  complexity: number;
  explicit: boolean;
  timestamp: number;
  narrative?: string;
  metrics?: Record<string, unknown>;
  matchedKeywords?: string[];
  pipelineTrail?: Array<{
    tier: string;
    strategy?: string;
    status: string;
    complexity?: number;
    score?: number;
    matchedKeywords?: string[];
    detail?: string;
  }>;
}
export interface CapabilityEvent { event: 'SEARCH' | 'LOAD' | 'EXECUTE'; snapshotId: string; query?: string; capabilityIds?: string[]; capabilityId?: string; registrySize?: number; timestamp: number }
export interface CapabilityCandidate { rank:number; capabilityId:string; type:string; group:string; name:string; version:number; riskLevel:string; score:number; selected:boolean }
export interface CapabilitySearch { id:string; snapshotId:string; parentToolCallId:string; agentName:string; query:string; requestedTypes:string[]; registrySize:number; resultCount:number; status:string; startedAt:number; completedAt:number; durationMs:number; error:string; candidates:CapabilityCandidate[] }
export interface CapabilityExecution { id:string; snapshotId:string; parentToolCallId:string; action:'LOAD'|'EXECUTE'; capabilityId:string; type:string; group:string; name:string; version:number; riskLevel:string; resourcePath:string; arguments:Record<string,unknown>; resultSummary:string; resultSize:number; resultHash:string; artifactId:string; status:string; startedAt:number; completedAt:number; durationMs:number; retryCount:number; error:string }
export interface AgentTask { id: string; title: string; taskType: string; status: string; createdAt: number; updatedAt: number; completedAt: number; version: number }
export interface RunStep { id: string; sequenceNo: number; stepType: string; stepName: string; agentName: string; status: string; startedAt: number; completedAt: number; durationMs: number; retryCount: number; outputSummary?: string; error?: string }
export interface AgentRun { runId: string; parentRunId: string; agentName: string; role: 'ROOT' | 'SUBAGENT'; status: string; startedAt: number; completedAt: number; durationMs: number; modelCallCount: number; toolCallCount: number; inputTokens: number; outputTokens: number; aggregated?: boolean; retryCount: number; error?: string }
export interface WaterfallItem { id: string; parentId: string; name: string; type: 'AGENT' | 'MODEL' | 'TOOL' | 'CAPABILITY'; status: string; startedAt: number; completedAt: number; durationMs: number; inputTokens: number; outputTokens: number }
export interface SubagentTask { taskId: string; parentRunId: string; childRunId: string; templateKey: string; task: string; status: string; result?: string; error?: string; createdAt: number; startedAt: number; completedAt: number; durationMs: number }
export interface EvaluationLink { runId: string; caseRunId: string; candidateLabel: string; caseName: string; score: number; passed: boolean }
export interface InvocationDetail extends InvocationItem { task?: AgentTask; steps?: RunStep[]; agentRuns?: AgentRun[]; waterfall?: WaterfallItem[]; subagentTasks?: SubagentTask[]; evaluations?: EvaluationLink[]; capabilitySearches?:CapabilitySearch[]; capabilityExecutions?:CapabilityExecution[]; agents: AgentExecution[]; models?: ModelExecution[]; tools: ToolExecution[]; compressions: CompressionRecord[]; modelDecisions: ModelDecision[]; capabilityEvents: CapabilityEvent[] }
