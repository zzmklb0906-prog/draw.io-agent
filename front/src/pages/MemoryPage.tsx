import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../features/auth/auth.store';
import { confirmMemory, consolidateMemories, createMemory, deleteMemory, queryMemories, queryMemoryEvidence, queryMemoryRetrieve, updateMemory } from '../features/memory/memory.api';
import type { AgentMemory } from '../features/memory/memory.types';

export function MemoryPage() {
  const user = useAuthStore((state) => state.user)!;
  const client = useQueryClient();
  const [projectId, setProjectId] = useState('draw-io-agent');
  const [type, setType] = useState('PROJECT_FACT');
  const [content, setContent] = useState('');
  const [structured, setStructured] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  const [editing, setEditing] = useState<AgentMemory>();
  const [evidenceMemoryId, setEvidenceMemoryId] = useState('');

  const memories = useQuery({
    queryKey: ['memories', user.username, projectId],
    queryFn: () => queryMemories(user.username, projectId),
    enabled: !activeSearch,
  });

  const retrieveQuery = useQuery({
    queryKey: ['memories-retrieve', user.username, projectId, activeSearch],
    queryFn: () => queryMemoryRetrieve(user.username, projectId, activeSearch),
    enabled: !!activeSearch,
  });

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['memories'] });
    if (activeSearch) void client.invalidateQueries({ queryKey: ['memories-retrieve'] });
  };

  const removeOrConfirm = useMutation({ mutationFn: async ({ id, kind }: { id: string; kind: 'confirm' | 'delete' }) => { if (kind === 'confirm') await confirmMemory(id); else await deleteMemory(id); }, onSuccess: refresh });
  const save = useMutation({ mutationFn: async () => { if (editing) return updateMemory(editing); if (!content.trim()) throw new Error('Memory 内容不能为空'); return createMemory({ userId: user.username, projectId, memoryType: type, content, structuredData: structured || undefined, importance: .7, confidence: .8, confirmed: true }); }, onSuccess: () => { setContent(''); setStructured(''); setEditing(undefined); refresh(); } });
  const evidence = useQuery({ queryKey: ['memory-evidence', evidenceMemoryId], queryFn: () => queryMemoryEvidence(evidenceMemoryId), enabled: !!evidenceMemoryId });
  const consolidate = useMutation({ mutationFn: () => consolidateMemories(projectId), onSuccess: refresh });
  const validateJson = (value: string) => { if (!value.trim()) return true; try { JSON.parse(value); return true; } catch { return false; } };
  const form = editing ?? { content, structuredData: structured, memoryType: type, projectId, importance: .7, confirmed: true } as Pick<AgentMemory, 'content' | 'structuredData' | 'memoryType' | 'projectId' | 'importance' | 'confirmed'>;
  const setForm = (key: 'content' | 'structuredData', value: string) => editing ? setEditing({ ...editing, [key]: value }) : key === 'content' ? setContent(value) : setStructured(value);

  const displayList = activeSearch ? (retrieveQuery.data ?? []) : (memories.data ?? []);
  const isLoading = activeSearch ? retrieveQuery.isLoading : memories.isLoading;
  const isError = activeSearch ? retrieveQuery.isError : memories.isError;
  const errorMessage = activeSearch ? retrieveQuery.error?.message : memories.error?.message;

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setActiveSearch(searchQuery.trim());
  };

  const handleClearSearch = () => {
    setSearchQuery('');
    setActiveSearch('');
  };

  return <main className="memory-page">
    <header className="monitor-header"><div><p className="eyebrow">LONG-TERM CONTEXT</p><h1>长期 Memory</h1><p>确认、编辑、追溯和删除跨 Session 的用户偏好与结构化项目事实。</p></div><nav><button className="button" disabled={consolidate.isPending} onClick={() => consolidate.mutate()}>合并重复候选</button><Link className="button" to="/workspace">绘图台</Link></nav></header>

    <section className="memory-search-bar" style={{ maxWidth: 1200, margin: '14px auto', display: 'flex', gap: 8, alignItems: 'center', background: 'var(--panel)', padding: 12, borderRadius: 12, border: '1px solid var(--line)' }}>
      <form onSubmit={handleSearchSubmit} style={{ display: 'flex', gap: 8, flex: 1 }}>
        <input
          style={{ flex: 1, padding: '8px 12px', borderRadius: 8, border: '1px solid var(--line)', background: '#fff', color: 'var(--ink)' }}
          placeholder="输入关键词或语义检索 Agent Memory（基于 GET /api/v1/memories/retrieve）…"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
        <button type="submit" className="button primary" disabled={!searchQuery.trim()}>检索</button>
        {activeSearch && (
          <button type="button" className="button" onClick={handleClearSearch}>清除检索</button>
        )}
      </form>
      {activeSearch && <span style={{ fontSize: 12, color: 'var(--muted)', whiteSpace: 'nowrap' }}>检索模式: 「{activeSearch}」</span>}
    </section>

    <section className="memory-editor"><h2>{editing ? '编辑 Memory' : '新增结构化 Memory'}</h2><div className="form-grid"><label className="field"><span>项目</span><input value={editing?.projectId ?? projectId} disabled={!!editing} onChange={(event) => setProjectId(event.target.value)} /></label><label className="field"><span>类型</span><select value={editing?.memoryType ?? type} disabled={!!editing} onChange={(event) => setType(event.target.value)}><option>USER_PREFERENCE</option><option>PROJECT_FACT</option><option>EPISODE</option><option>PROCEDURE</option><option>TASK_LESSON</option></select></label></div><label className="field"><span>自然语言摘要</span><textarea value={form.content} onChange={(event) => setForm('content', event.target.value)} /></label><label className="field"><span>结构化 JSON（Draw.io 项目状态建议保存 nodes、edges、constraints、artifactId）</span><textarea className={!validateJson(form.structuredData ?? '') ? 'invalid' : ''} value={form.structuredData ?? ''} onChange={(event) => setForm('structuredData', event.target.value)} /></label><div className="modal-actions">{editing && <button className="button" onClick={() => setEditing(undefined)}>取消</button>}<button className="button primary" disabled={!form.content.trim() || !validateJson(form.structuredData ?? '') || save.isPending} onClick={() => save.mutate()}>保存 Memory</button></div></section>
    {consolidate.data && <div className={`runtime-banner ${consolidate.data.conflictCount ? 'bad' : 'ok'}`}><strong>合并完成</strong><span>扫描 {consolidate.data.scanned} 条，合并 {consolidate.data.merged} 条；发现 {consolidate.data.conflictCount} 组结构化冲突，冲突项不会自动覆盖。</span></div>}
    {consolidate.data?.conflicts.map((conflict, index) => <pre className="memory-conflict" key={index}>{JSON.stringify(conflict, null, 2)}</pre>)}

    {isLoading ? (
      <p className="monitor-empty">正在加载 Memory 记录…</p>
    ) : isError ? (
      <div className="monitor-detail-error" style={{ maxWidth: 1200, margin: '10px auto' }}>加载失败：{errorMessage}</div>
    ) : (
      <section className="memory-grid">
        {displayList.map((memory) => (
          <article key={memory.memoryId}>
            <div><i>{memory.memoryType}</i><span>{memory.confirmed ? '已确认' : '待确认'}</span></div>
            <h2>{memory.content}</h2>
            {memory.structuredData && <pre>{memory.structuredData}</pre>}
            <small>重要度 {memory.importance.toFixed(2)} · 置信度 {memory.confidence.toFixed(2)} · {new Date(memory.updatedAt).toLocaleString()}</small>
            {evidenceMemoryId === memory.memoryId && (
              <div className="memory-evidence">
                <strong>来源证据</strong>
                {evidence.data?.map((item) => <code key={`${item.evidenceType}-${item.evidenceId}`}>{item.evidenceType} · {item.evidenceId}</code>)}
                {!evidence.data?.length && <small>没有可追溯证据。</small>}
              </div>
            )}
            <footer>
              <button className="button tiny" onClick={() => setEditing(memory)}>编辑</button>
              <button className="button tiny" onClick={() => setEvidenceMemoryId((current) => current === memory.memoryId ? '' : memory.memoryId)}>来源</button>
              {!memory.confirmed && <button className="button primary tiny" onClick={() => removeOrConfirm.mutate({ id: memory.memoryId, kind: 'confirm' })}>确认</button>}
              <button className="button danger tiny" onClick={() => removeOrConfirm.mutate({ id: memory.memoryId, kind: 'delete' })}>删除</button>
            </footer>
          </article>
        ))}
        {!displayList.length && (
          <p className="monitor-empty">{activeSearch ? `未检索到匹配「${activeSearch}」的 Memory。` : '尚无长期 Memory。'}</p>
        )}
      </section>
    )}
  </main>;
}
