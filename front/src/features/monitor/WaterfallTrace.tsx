import type { WaterfallItem } from './monitor.types';

const duration = (ms: number) => ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;

export function WaterfallTrace({ items }: { items: WaterfallItem[] }) {
  if (!items.length) return <p className="monitor-empty compact">当前 Invocation 暂无时间轴数据。</p>;
  const start = Math.min(...items.map(item => item.startedAt));
  const end = Math.max(...items.map(item => item.completedAt || Date.now()), start + 1);
  const span = Math.max(1, end - start);
  const indexed = new Map(items.map(item => [item.id, item]));
  const depth = (item: WaterfallItem) => { let value = item.type === 'AGENT' ? 0 : 1; let parent = indexed.get(item.parentId); const seen = new Set<string>(); while (parent && !seen.has(parent.id)) { seen.add(parent.id); if(parent.type!=='AGENT'||parent.parentId)value += 1; parent = indexed.get(parent.parentId); } return Math.min(value, 6); };
  return <div className="waterfall-trace">
    <div className="waterfall-axis"><span>0</span><span>{duration(span / 2)}</span><span>{duration(span)}</span></div>
    {items.map(item => { const left = ((item.startedAt - start) / span) * 100; const width = Math.max(.7, ((item.completedAt || Date.now()) - item.startedAt) / span * 100); return <article className="waterfall-row" key={`${item.type}-${item.id}`}>
      <div className="waterfall-label" style={{ paddingLeft: `${depth(item) * 13 + 10}px` }}><i>{item.type}</i><strong title={item.name}>{item.name}</strong><small>{duration(item.durationMs)}</small></div>
      <div className="waterfall-lane"><span className={`waterfall-bar ${item.type.toLowerCase()} ${item.status.toLowerCase()}`} style={{ left: `${left}%`, width: `${Math.min(width, 100 - left)}%` }} title={`${item.name} · ${duration(item.durationMs)} · ${item.status}`} /></div>
      <div className="waterfall-facts">{item.inputTokens || item.outputTokens ? `${(item.inputTokens + item.outputTokens).toLocaleString()} tok` : item.status}</div>
    </article>; })}
  </div>;
}
