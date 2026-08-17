import type { AgentConfig } from '../agents.types';

interface Props {
  agents: AgentConfig[];
  value: string;
  disabled?: boolean;
  onChange: (agentId: string) => void;
}

export function AgentSelector({ agents, value, disabled, onChange }: Props) {
  return (
    <label className="field compact-field">
      <span>智能体</span>
      <select value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
        {agents.map((agent) => (
          <option key={agent.agentId} value={agent.agentId}>
            {agent.agentName}
          </option>
        ))}
      </select>
    </label>
  );
}
