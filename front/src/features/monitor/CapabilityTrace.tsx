import type { CapabilityExecution, CapabilitySearch } from './monitor.types';

const duration=(ms:number)=>ms<1000?`${ms} ms`:`${(ms/1000).toFixed(1)} s`;
const size=(bytes:number)=>bytes<1024?`${bytes} B`:`${(bytes/1024).toFixed(1)} KiB`;

export function CapabilityTrace({searches,executions}:{searches:CapabilitySearch[];executions:CapabilityExecution[]}){
  if(!searches.length&&!executions.length)return <p className="monitor-empty compact">本次未触发动态能力检索。</p>;
  return <div className="capability-trace">
    {searches.map(search=><article className="capability-search" key={search.id}>
      <header><div><strong>SEARCH · {search.query}</strong><small>{search.agentName} · {search.resultCount}/{search.registrySize} 项 · {duration(search.durationMs)}</small></div><i className={`monitor-status ${search.status.toLowerCase()}`}>{search.status}</i></header>
      <div className="capability-candidates">{search.candidates.map(candidate=><div className={candidate.selected?'selected':''} key={`${search.id}-${candidate.rank}`}><b>#{candidate.rank}</b><span><strong>{candidate.name}</strong><small>{candidate.type} · {candidate.group} · v{candidate.version}</small></span><em>{candidate.score.toFixed(2)}</em>{candidate.selected&&<i>SELECTED</i>}</div>)}</div>
    </article>)}
    {executions.map(execution=><article className={`capability-execution ${execution.status.toLowerCase()}`} key={execution.id}>
      <header><div><strong>{execution.action} · {execution.name}</strong><small>{execution.type} · {execution.group} · v{execution.version} · {execution.riskLevel}</small></div><span>{duration(execution.durationMs)}</span><i className={`monitor-status ${execution.status.toLowerCase()}`}>{execution.status}</i></header>
      <dl><div><dt>Capability ID</dt><dd>{execution.capabilityId}</dd></div>{execution.resourcePath&&<div><dt>Resource</dt><dd>{execution.resourcePath}</dd></div>}<div><dt>结果</dt><dd>{size(execution.resultSize)} · SHA-256 {execution.resultHash.slice(0,12)||'—'}</dd></div>{execution.artifactId&&<div><dt>Artifact</dt><dd>{execution.artifactId}</dd></div>}</dl>
      <details><summary>参数与结果摘要</summary><pre>{JSON.stringify(execution.arguments,null,2)}</pre>{execution.resultSummary&&<pre>{execution.resultSummary}</pre>}</details>{execution.error&&<p className="tool-error">{execution.error}</p>}
    </article>)}
  </div>;
}
