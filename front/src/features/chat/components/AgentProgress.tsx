import type { StreamPhase } from '../chat.types';

const labels: Record<StreamPhase, string> = {
  idle: '等待任务', thinking: '思考中', analyzing: '分析需求', drawing: '绘制图表', generating: '生成内容', reviewing: '检查优化', done: '已完成', error: '执行失败',
};

export function AgentProgress({ phase, status, nodes, edges }: { phase: StreamPhase; status: string; nodes: number; edges: number }) {
  return (
    <div className={`agent-progress phase-${phase}`}>
      <span className="status-dot" aria-hidden="true" />
      <strong>{labels[phase]}</strong>
      <span>{status}</span>
      {(nodes > 0 || edges > 0) && <span className="progress-count">节点 {nodes} · 连线 {edges}</span>}
    </div>
  );
}
