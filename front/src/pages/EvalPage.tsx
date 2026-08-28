import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { createEvalCase, createEvalDataset, queryEvalDataset, queryEvalDatasets, queryEvalRun, queryEvalRuns, setEvalBaseline, startEvalRun, updateEvalCase } from '../features/eval/eval.api';
import type { EvalCase } from '../features/eval/eval.types';

const duration=(ms:number)=>ms<1000?`${Math.round(ms)} ms`:`${(ms/1000).toFixed(1)} s`;
const terminal=(status?:string)=>['COMPLETED','FAILED','CANCELLED'].includes(status??'');

export function EvalPage(){
  const client=useQueryClient();const [params,setParams]=useSearchParams();const [datasetId,setDatasetId]=useState(params.get('dataset')??'');const [runId,setRunId]=useState(params.get('run')??'');const [label,setLabel]=useState(`candidate-${new Date().toISOString().slice(0,16)}`);const [repeats,setRepeats]=useState(1);const [showDataset,setShowDataset]=useState(false);const [showCase,setShowCase]=useState(false);const [editingCase,setEditingCase]=useState<EvalCase|null>(null);
  const datasets=useQuery({queryKey:['eval-datasets'],queryFn:queryEvalDatasets});useEffect(()=>{if(!datasetId&&datasets.data?.length)setDatasetId(datasets.data[0].id)},[datasetId,datasets.data]);
  const dataset=useQuery({queryKey:['eval-dataset',datasetId],queryFn:()=>queryEvalDataset(datasetId),enabled:!!datasetId,refetchInterval:runId?2000:false});
  const runs=useQuery({queryKey:['eval-runs',datasetId],queryFn:()=>queryEvalRuns(datasetId),enabled:!!datasetId,refetchInterval:2000});
  const run=useQuery({queryKey:['eval-run',runId],queryFn:()=>queryEvalRun(runId),enabled:!!runId,refetchInterval:q=>terminal(q.state.data?.status)?false:1500});
  useEffect(()=>{setParams(old=>{const next=new URLSearchParams(old);if(datasetId)next.set('dataset',datasetId);else next.delete('dataset');if(runId)next.set('run',runId);else next.delete('run');return next},{replace:true})},[datasetId,runId,setParams]);
  const start=useMutation({mutationFn:()=>startEvalRun({datasetId,candidateLabel:label,repeats,baselineRunId:dataset.data?.baselineRunId||undefined}),onSuccess:data=>{setRunId(data.runId);void client.invalidateQueries({queryKey:['eval-runs']})}});
  const baseline=useMutation({mutationFn:()=>setEvalBaseline(datasetId,runId),onSuccess:()=>{void client.invalidateQueries({queryKey:['eval-dataset',datasetId]});void client.invalidateQueries({queryKey:['eval-datasets']})}});
  const progress=run.data?.totalCases?Math.round(run.data.completedCases/run.data.totalCases*100):0;const selectedCaseRuns=useMemo(()=>run.data?.caseRuns??[],[run.data?.caseRuns]);
  const hardFailures=useMemo(()=>selectedCaseRuns.flatMap(c=>c.assertions??[]).filter(a=>a.hardGate&&!a.passed).length,[selectedCaseRuns]);
  return <main className="eval-page"><header className="monitor-header"><div><p className="eyebrow">AGENT QUALITY</p><h1>Agent Eval 平台</h1><p>Dataset · Trace Grading · Baseline Compare · Release Gate</p></div><nav><Link className="button" to="/monitor">运行监控</Link><Link className="button" to="/workspace">返回工作台</Link></nav></header>
    <section className="eval-toolbar"><select value={datasetId} onChange={e=>{setDatasetId(e.target.value);setRunId('')}}>{datasets.data?.map(d=><option key={d.id} value={d.id}>{d.name} · {d.caseCount} Cases</option>)}</select><input value={label} onChange={e=>setLabel(e.target.value)} aria-label="候选版本"/><label>重复 <input type="number" min={1} max={10} value={repeats} onChange={e=>setRepeats(Number(e.target.value))}/></label><button className="button primary" disabled={!datasetId||start.isPending} onClick={()=>start.mutate()}>{start.isPending?'提交中…':'运行 Benchmark'}</button><button className="button" onClick={()=>{setEditingCase(null);setShowCase(true)}} disabled={!datasetId}>新增 Case</button><button className="button" onClick={()=>setShowDataset(true)}>新增数据集</button></section>
    {run.data&&<section className="metric-grid eval-metrics"><article><span>平均分</span><strong>{run.data.averageScore.toFixed(1)}</strong></article><article><span>门禁</span><strong>{run.data.gateStatus}</strong></article><article><span>通过 / 失败</span><strong>{run.data.passedCases} / {run.data.failedCases}</strong></article><article><span>回退</span><strong>{run.data.regressionCount}</strong></article><article><span>硬门禁失败</span><strong>{hardFailures}</strong></article><article><span>Tokens</span><strong>{(run.data.totalInputTokens+run.data.totalOutputTokens).toLocaleString()}</strong></article></section>}
    <div className="eval-layout"><aside className="eval-sidebar"><h2>数据集</h2>{datasets.data?.map(d=><button className={datasetId===d.id?'active':''} key={d.id} onClick={()=>{setDatasetId(d.id);setRunId('')}}><strong>{d.name}</strong><small>{d.key} · v{d.version}</small><span>{d.caseCount} Cases{d.baselineRunId?' · 已设基线':''}</span></button>)}<h2>运行记录</h2>{runs.data?.map(r=><button className={runId===r.id?'active':''} key={r.id} onClick={()=>setRunId(r.id)}><strong>{r.candidateLabel}</strong><small>{r.status} · {r.averageScore.toFixed(1)} 分</small><span>{new Date(r.createdAt).toLocaleString()}</span></button>)}</aside>
      <section className="eval-content">{!runId&&<><div className="eval-section-title"><div><h2>{dataset.data?.name}</h2><p>{dataset.data?.description}</p></div><span>Baseline {dataset.data?.baselineRunId?.slice(0,8)||'未设置'}</span></div><div className="eval-case-grid">{dataset.data?.cases?.map(c=><article key={c.id}><header><strong>{c.name}</strong><div style={{display:'flex',alignItems:'center',gap:6}}><button type="button" className="button tiny" onClick={()=>{setEditingCase(c);setShowCase(true)}}>编辑</button><i>{c.enabled?'ENABLED':'DISABLED'}</i></div></header><p>{c.prompt}</p><dl><div><dt>Agent</dt><dd>{c.agentId}</dd></div><div><dt>版本</dt><dd>v{c.version}</dd></div></dl><details><summary>评分契约</summary><pre>{JSON.stringify(c.expectations,null,2)}</pre></details></article>)}</div></>}
      {runId&&run.data&&<><div className="eval-section-title"><div><h2>{run.data.candidateLabel}</h2><p>{run.data.status} · {run.data.completedCases}/{run.data.totalCases} Case Runs · 平均耗时 {duration(run.data.averageDurationMs)}</p></div><button className="button tiny" disabled={run.data.status!=='COMPLETED'||baseline.isPending} onClick={()=>baseline.mutate()}>设为 Baseline</button></div><div className="eval-progress"><span style={{width:`${progress}%`}}/><b>{progress}%</b></div>{run.data.errorMessage&&<div className="monitor-detail-error">{run.data.errorMessage}</div>}<div className="eval-results">{selectedCaseRuns.map(cr=><article className={cr.passed?'passed':'failed'} key={cr.id}><header><div><strong>{cr.caseName}</strong><small>Repeat #{cr.repeatIndex} · {cr.agentId}</small></div><b>{cr.totalScore.toFixed(1)}</b><i>{cr.status}</i></header><div className="eval-result-facts"><span>{duration(cr.durationMs)}</span><span>{cr.toolCalls} Tools</span><span>{cr.modelCalls} Models</span><span>{(cr.inputTokens+cr.outputTokens).toLocaleString()} Tokens</span>{cr.invocationId&&<Link to={`/monitor?invocation=${encodeURIComponent(cr.invocationId)}`}>查看 Trace</Link>}</div><div className="eval-score-bars">{Object.entries(cr.scoreBreakdown??{}).filter(([,v])=>typeof v==='number').map(([k,v])=><span key={k}><label>{k}</label><progress max={k==='toolPrecision'?1:100} value={v}/><b>{v}</b></span>)}</div><details><summary>断言结果（{cr.assertions?.filter(a=>a.passed).length??0}/{cr.assertions?.length??0}）</summary><div className="eval-assertions">{cr.assertions?.map(a=><div className={a.passed?'pass':'fail'} key={a.id}><b>{a.passed?'✓':'×'}</b><span><strong>{a.description}</strong><small>{a.graderType}{a.hardGate?' · HARD GATE':''}</small></span><i>{a.score}/{a.maxScore}</i></div>)}</div></details>{cr.errorMessage&&<p className="tool-error">{cr.errorMessage}</p>}</article>)}</div></>}</section></div>
    {showDataset&&<DatasetDialog close={()=>setShowDataset(false)} done={id=>{setShowDataset(false);setDatasetId(id);void client.invalidateQueries({queryKey:['eval-datasets']})}}/>}
    {showCase&&<CaseDialog initialCase={editingCase} datasetId={datasetId} close={()=>{setShowCase(false);setEditingCase(null)}} done={()=>{setShowCase(false);setEditingCase(null);void client.invalidateQueries({queryKey:['eval-dataset',datasetId]});void client.invalidateQueries({queryKey:['eval-datasets']})}}/>}
  </main>;
}

function DatasetDialog({close,done}:{close:()=>void;done:(id:string)=>void}){const [key,setKey]=useState('');const [name,setName]=useState('');const [description,setDescription]=useState('');const mutation=useMutation({mutationFn:()=>createEvalDataset({key,name,description}),onSuccess:r=>done(r.datasetId)});return <div className="eval-modal"><form onSubmit={e=>{e.preventDefault();mutation.mutate()}}><h2>新增 Eval 数据集</h2><input placeholder="dataset-key" value={key} onChange={e=>setKey(e.target.value)} required/><input placeholder="数据集名称" value={name} onChange={e=>setName(e.target.value)} required/><textarea placeholder="用途和覆盖范围" value={description} onChange={e=>setDescription(e.target.value)}/><footer><button type="button" className="button" onClick={close}>取消</button><button className="button primary">保存</button></footer></form></div>}

function CaseDialog({initialCase,datasetId,close,done}:{initialCase?:EvalCase|null;datasetId:string;close:()=>void;done:()=>void}){
  const isEdit=!!initialCase;
  const [key,setKey]=useState(initialCase?.caseKey||'');
  const [name,setName]=useState(initialCase?.name||'');
  const [agentId,setAgentId]=useState(initialCase?.agentId||'300002');
  const [prompt,setPrompt]=useState(initialCase?.prompt||'');
  const [expectations,setExpectations]=useState(()=>initialCase?.expectations ? JSON.stringify(initialCase.expectations, null, 2) : '{\n  "requiredText": [],\n  "requiredTools": [],\n  "forbiddenTools": [],\n  "maxTokens": 20000,\n  "passScore": 75\n}');
  const [enabled,setEnabled]=useState(initialCase?.enabled ?? true);

  const mutation=useMutation<void, Error, void>({
    mutationFn: async ()=>{
      const parsedExpectations=JSON.parse(expectations);
      const rubric=initialCase?.rubric || {contentWeight:50,trajectoryWeight:40,efficiencyWeight:10};
      const tags=initialCase?.tags || [];
      if(isEdit && initialCase){
        await updateEvalCase(initialCase.id,{
          name,
          agentId,
          prompt,
          expectations:parsedExpectations,
          rubric,
          tags,
          enabled,
        });
      } else {
        await createEvalCase(datasetId,{
          caseKey:key,
          name,
          agentId,
          prompt,
          expectations:parsedExpectations,
          rubric,
          tags,
          enabled,
        });
      }
    },
    onSuccess:done,
  });

  return <div className="eval-modal"><form onSubmit={e=>{e.preventDefault();mutation.mutate()}}><h2>{isEdit ? '编辑 Benchmark Case' : '新增 Benchmark Case'}</h2><input placeholder="case-key" value={key} onChange={e=>setKey(e.target.value)} disabled={isEdit} required/><input placeholder="Case 名称" value={name} onChange={e=>setName(e.target.value)} required/><select value={agentId} onChange={e=>setAgentId(e.target.value)}><option value="300002">通用 Agent</option><option value="300000">Draw.io Agent</option><option value="300001">PPT Agent</option></select><textarea placeholder="用户输入 Prompt" value={prompt} onChange={e=>setPrompt(e.target.value)} required/><textarea className="json-editor" value={expectations} onChange={e=>setExpectations(e.target.value)}/>{isEdit&&<label style={{display:'flex',alignItems:'center',gap:8,fontSize:12,color:'var(--ink)'}}><input type="checkbox" checked={enabled} onChange={e=>setEnabled(e.target.checked)}/> 启用该用例 (Enabled)</label>}{mutation.error&&<p className="tool-error">{mutation.error.message}</p>}<footer><button type="button" className="button" onClick={close}>取消</button><button className="button primary" disabled={mutation.isPending}>{mutation.isPending ? '保存中…' : '保存'}</button></footer></form></div>;
}
