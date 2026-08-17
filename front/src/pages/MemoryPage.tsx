import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../features/auth/auth.store';
import { confirmMemory, consolidateMemories, createMemory, deleteMemory, queryMemories, queryMemoryEvidence, updateMemory } from '../features/memory/memory.api';
import type { AgentMemory } from '../features/memory/memory.types';

export function MemoryPage() {
  const user = useAuthStore((state) => state.user)!;
  const client = useQueryClient();
  const [projectId, setProjectId] = useState('draw-io-agent');
  const [type, setType] = useState('PROJECT_FACT');
  const [content, setContent] = useState('');
  const [structured, setStructured] = useState('');
  const [editing, setEditing] = useState<AgentMemory>();
  const [evidenceMemoryId, setEvidenceMemoryId] = useState('');
  const memories = useQuery({ queryKey: ['memories', user.username, projectId], queryFn: () => queryMemories(user.username, projectId) });
  const refresh = () => void client.invalidateQueries({ queryKey: ['memories'] });
  const removeOrConfirm = useMutation({ mutationFn: async ({ id, kind }: { id: string; kind: 'confirm' | 'delete' }) => { if (kind === 'confirm') await confirmMemory(id); else await deleteMemory(id); }, onSuccess: refresh });
  const save = useMutation({ mutationFn: async () => { if (editing) return updateMemory(editing); if (!content.trim()) throw new Error('Memory 内容不能为空'); return createMemory({ userId: user.username, projectId, memoryType: type, content, structuredData: structured || undefined, importance: .7, confidence: .8, confirmed: true }); }, onSuccess: () => { setContent(''); setStructured(''); setEditing(undefined); refresh(); } });
  const evidence = useQuery({ queryKey: ['memory-evidence', evidenceMemoryId], queryFn: () => queryMemoryEvidence(evidenceMemoryId), enabled: !!evidenceMemoryId });
  const consolidate = useMutation({ mutationFn: () => consolidateMemories(projectId), onSuccess: refresh });
  const validateJson = (value: string) => { if (!value.trim()) return true; try { JSON.parse(value); return true; } catch { return false; } };
  const form = editing ?? { content, structuredData: structured, memoryType: type, projectId, importance: .7, confirmed: true } as Pick<AgentMemory, 'content' | 'structuredData' | 'memoryType' | 'projectId' | 'importance' | 'confirmed'>;
  const setForm = (key: 'content' | 'structuredData', value: string) => editing ? setEditing({ ...editing, [key]: value }) : key === 'content' ? setContent(value) : setStructured(value);

  return <main className="memory-page">
    <header className="monitor-header"><div><p className="eyebrow">LONG-TERM CONTEXT</p><h1>长期 Memory</h1><p>确认、编辑、追溯和删除跨 Session 的用户偏好与结构化项目事实。</p></div><nav><button className="button" disabled={consolidate.isPending} onClick={() => consolidate.mutate()}>合并重复候选</button><Link className="button" to="/workspace">绘图台</Link></nav></header>
    <section className="memory-editor"><h2>{editing ? '编辑 Memory' : '新增结构化 Memory'}</h2><div className="form-grid"><label className="field"><span>项目</span><input value={editing?.projectId ?? projectId} disabled={!!editing} onChange={(event) => setProjectId(event.target.value)} /></label><label className="field"><span>类型</span><select value={editing?.memoryType ?? type} disabled={!!editing} onChange={(event) => setType(event.target.value)}><option>USER_PREFERENCE</option><option>PROJECT_FACT</option><option>EPISODE</option><option>PROCEDURE</option><option>TASK_LESSON</option></select></label></div><label className="field"><span>自然语言摘要</span><textarea value={form.content} onChange={(event) => setForm('content', event.target.value)} /></label><label className="field"><span>结构化 JSON（Draw.io 项目状态建议保存 nodes、edges、constraints、artifactId）</span><textarea className={!validateJson(form.structuredData ?? '') ? 'invalid' : ''} value={form.structuredData ?? ''} onChange={(event) => setForm('structuredData', event.target.value)} /></label><div className="modal-actions">{editing && <button className="button" onClick={() => setEditing(undefined)}>取消</button>}<button className="button primary" disabled={!form.content.trim() || !validateJson(form.structuredData ?? '') || save.isPending} onClick={() => save.mutate()}>保存 Memory</button></div></section>
    {consolidate.data && <div className={`runtime-banner ${consolidate.data.conflictCount ? 'bad' : 'ok'}`}><strong>合并完成</strong><span>扫描 {consolidate.data.scanned} 条，合并 {consolidate.data.merged} 条；发现 {consolidate.data.conflictCount} 组结构化冲突，冲突项不会自动覆盖。</span></div>}
    {consolidate.data?.conflicts.map((conflict, index) => <pre className="memory-conflict" key={index}>{JSON.stringify(conflict, null, 2)}</pre>)}
    <section className="memory-grid">{memories.data?.map((memory) => <article key={memory.memoryId}><div><i>{memory.memoryType}</i><span>{memory.confirmed ? '已确认' : '待确认'}</span></div><h2>{memory.content}</h2>{memory.structuredData && <pre>{memory.structuredData}</pre>}<small>重要度 {memory.importance.toFixed(2)} · 置信度 {memory.confidence.toFixed(2)} · {new Date(memory.updatedAt).toLocaleString()}</small>{evidenceMemoryId === memory.memoryId && <div className="memory-evidence"><strong>来源证据</strong>{evidence.data?.map((item) => <code key={`${item.evidenceType}-${item.evidenceId}`}>{item.evidenceType} · {item.evidenceId}</code>)}{!evidence.data?.length && <small>没有可追溯证据。</small>}</div>}<footer><button className="button tiny" onClick={() => setEditing(memory)}>编辑</button><button className="button tiny" onClick={() => setEvidenceMemoryId((current) => current === memory.memoryId ? '' : memory.memoryId)}>来源</button>{!memory.confirmed && <button className="button primary tiny" onClick={() => removeOrConfirm.mutate({ id: memory.memoryId, kind: 'confirm' })}>确认</button>}<button className="button danger tiny" onClick={() => removeOrConfirm.mutate({ id: memory.memoryId, kind: 'delete' })}>删除</button></footer></article>)}{!memories.data?.length && <p className="monitor-empty">尚无长期 Memory。</p>}</section>
  </main>;
}
