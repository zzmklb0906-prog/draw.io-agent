import { request } from '../../shared/api/httpClient';
import type { AgentConfig } from './agents.types';

export const queryAgents = () =>
  request<AgentConfig[]>('/api/v1/query_ai_agent_config_list');
