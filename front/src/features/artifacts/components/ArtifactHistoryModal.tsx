import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useDiagramStore } from '../../diagram/diagram.store';
import { branchArtifact, queryArtifactDiff, queryArtifacts, rollbackArtifact } from '../artifacts.api';
import type { ArtifactView } from '../artifacts.types';

const BRANCH_NAME_REGEX = /^[a-zA-Z0-9._-]{1,120}$/;

interface ArtifactHistoryModalProps {
  conversationId: string;
  onClose: () => void;
}

export function ArtifactHistoryModal({ conversationId, onClose }: ArtifactHistoryModalProps) {
  const client = useQueryClient();
  const diagram = useDiagramStore();
  const [selectedId, setSelectedId] = useState<string>('');
  const [diffBaseId, setDiffBaseId] = useState<string>('');
  const [newBranchName, setNewBranchName] = useState('');
  const [branchError, setBranchError] = useState('');
  const [actionNotice, setActionNotice] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const artifactsQuery = useQuery({
    queryKey: ['artifacts', conversationId],
    queryFn: () => queryArtifacts(conversationId),
    enabled: !!conversationId,
  });

  const artifacts = useMemo(() => artifactsQuery.data ?? [], [artifactsQuery.data]);

  const selectedArtifact = useMemo(
    () => artifacts.find((a) => a.id === selectedId) || (artifacts.length > 0 ? artifacts[0] : null),
    [artifacts, selectedId]
  );

  const activeId = selectedArtifact?.id || '';

  // Same lineage candidates for diff comparison
  const lineageCandidates = useMemo(() => {
    if (!selectedArtifact) return [];
    return artifacts.filter((a) => a.lineageId === selectedArtifact.lineageId && a.id !== selectedArtifact.id);
  }, [artifacts, selectedArtifact]);

  const effectiveDiffBaseId = diffBaseId && lineageCandidates.some((c) => c.id === diffBaseId)
    ? diffBaseId
    : (lineageCandidates[0]?.id || '');

  const diffQuery = useQuery({
    queryKey: ['artifact-diff', activeId, effectiveDiffBaseId],
    queryFn: () => queryArtifactDiff(activeId, effectiveDiffBaseId),
    enabled: !!activeId && !!effectiveDiffBaseId,
  });

  const refreshArtifacts = () => {
    void client.invalidateQueries({ queryKey: ['artifacts', conversationId] });
  };

  const rollbackMutation = useMutation({
    mutationFn: async (artifact: ArtifactView) => {
      const idempotencyKey = crypto.randomUUID();
      return rollbackArtifact(artifact.id, {
        invocationId: artifact.invocationId || undefined,
        idempotencyKey,
      });
    },
    onSuccess: (updated) => {
      setActionNotice({ type: 'success', message: `已成功回滚至版本 v${updated.versionNo} (${updated.branchName})` });
      refreshArtifacts();
    },
    onError: (err) => {
      setActionNotice({ type: 'error', message: `回滚失败：${err.message}` });
    },
  });

  const branchMutation = useMutation({
    mutationFn: async ({ artifact, branchName }: { artifact: ArtifactView; branchName: string }) => {
      const idempotencyKey = crypto.randomUUID();
      return branchArtifact(artifact.id, {
        invocationId: artifact.invocationId || undefined,
        idempotencyKey,
        branchName,
      });
    },
    onSuccess: (updated) => {
      setNewBranchName('');
      setBranchError('');
      setActionNotice({ type: 'success', message: `已从 v${updated.versionNo} 创建新分支 [${updated.branchName}]` });
      refreshArtifacts();
    },
    onError: (err) => {
      setActionNotice({ type: 'error', message: `创建分支失败：${err.message}` });
    },
  });

  const handleApplyToCanvas = (artifact: ArtifactView) => {
    if (artifact.artifactType !== 'DRAWIO' && !artifact.content.includes('<mxfile') && !artifact.content.includes('<mxGraphModel')) {
      setActionNotice({ type: 'error', message: '该产物不是 Draw.io 图表，无法应用到画布。' });
      return;
    }
    const success = diagram.applyAiXml(artifact.content);
    if (success) {
      setActionNotice({ type: 'success', message: `已将产物「${artifact.name}」v${artifact.versionNo} 应用到工作台画布。` });
    } else {
      setActionNotice({ type: 'error', message: diagram.error || '图表 XML 校验未通过，应用失败。' });
    }
  };

  const handleCreateBranch = () => {
    if (!selectedArtifact) return;
    const name = newBranchName.trim();
    if (!name || !BRANCH_NAME_REGEX.test(name)) {
      setBranchError('分支名必须由 1-120 位字母、数字、点(.)、下划线(_)或横杠(-)组成');
      return;
    }
    setBranchError('');
    branchMutation.mutate({ artifact: selectedArtifact, branchName: name });
  };

  return (
    <div className="modal-backdrop">
      <div className="modal-card artifact-history-modal" style={{ width: 'min(980px, 98%)', maxHeight: '90vh', display: 'flex', flexDirection: 'column' }}>
        <header className="modal-header">
          <div>
            <p className="eyebrow" style={{ margin: 0 }}>VERSION CONTROL</p>
            <h2 style={{ fontSize: 22, margin: '4px 0' }}>会话产物与版本管理 (Artifacts)</h2>
            <p style={{ margin: 0, fontSize: 12, color: 'var(--muted)' }}>
              会话 ID: {conversationId ? conversationId.slice(0, 18) : '未指定会话'}
            </p>
          </div>
          <button type="button" className="button ghost" onClick={onClose}>✕</button>
        </header>

        {actionNotice && (
          <div className={`notice-bar ${actionNotice.type === 'error' ? 'error' : ''}`} style={{ margin: '10px 0' }}>
            <span>{actionNotice.message}</span>
            <button type="button" onClick={() => setActionNotice(null)}>关闭</button>
          </div>
        )}

        {!conversationId ? (
          <p className="monitor-empty">请先选择或创建一个有效会话。</p>
        ) : artifactsQuery.isLoading ? (
          <p className="monitor-empty">正在拉取会话产物列表…</p>
        ) : artifactsQuery.isError ? (
          <div className="monitor-detail-error" style={{ margin: '14px 0', padding: 12 }}>
            <p style={{ margin: 0, fontWeight: 700 }}>拉取产物列表失败：{artifactsQuery.error.message}</p>
            <button type="button" className="button tiny" style={{ marginTop: 8 }} onClick={() => void artifactsQuery.refetch()}>
              重试
            </button>
          </div>
        ) : artifacts.length === 0 ? (
          <p className="monitor-empty">当前会话尚未产生任何 Artifact 产物。</p>
        ) : (
          <div className="artifact-modal-layout" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 14, flex: 1, minHeight: 0, overflow: 'hidden', marginTop: 12 }}>
            {/* Left list */}
            <aside className="artifact-modal-sidebar" style={{ maxHeight: 420, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 8, paddingRight: 4 }}>
              {artifacts.map((a) => {
                const isSelected = a.id === activeId;
                return (
                  <button
                    key={a.id}
                    type="button"
                    className={`artifact-item-card ${isSelected ? 'active' : ''}`}
                    onClick={() => {
                      setSelectedId(a.id);
                      setDiffBaseId('');
                    }}
                    style={{
                      padding: '10px 12px',
                      borderRadius: 10,
                      border: isSelected ? '1px solid var(--primary)' : '1px solid var(--line)',
                      background: isSelected ? '#f2dfaa' : '#fffaf0',
                      textAlign: 'left',
                      cursor: 'pointer',
                      display: 'grid',
                      gap: 4,
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <strong style={{ fontSize: 13, color: 'var(--ink)' }}>{a.name}</strong>
                      <span className="task-status completed" style={{ fontSize: 9 }}>{a.branchName}/v{a.versionNo}</span>
                    </div>
                    <small style={{ color: 'var(--muted)', fontSize: 11 }}>
                      {a.artifactType} · {(a.sizeBytes || 0).toLocaleString()} B
                    </small>
                    <small style={{ color: 'var(--muted)', fontSize: 10 }}>
                      {new Date(a.createdAt).toLocaleString('zh-CN')}
                    </small>
                  </button>
                );
              })}
            </aside>

            {/* Right details & actions */}
            <section className="artifact-modal-main" style={{ display: 'flex', flexDirection: 'column', gap: 12, overflowY: 'auto', paddingRight: 6 }}>
              {selectedArtifact && (
                <>
                  <div className="artifact-details-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 8, padding: '10px 12px', background: '#fcf6e8', borderRadius: 10, border: '1px solid var(--line)' }}>
                    <div>
                      <h3 style={{ margin: 0, fontSize: 16 }}>{selectedArtifact.name} (v{selectedArtifact.versionNo})</h3>
                      <p style={{ margin: '4px 0 0', fontSize: 11, color: 'var(--muted)' }}>
                        分支: <b>{selectedArtifact.branchName}</b> | 谱系 ID: {selectedArtifact.lineageId?.slice(0, 8)} | SHA-256: {selectedArtifact.contentHash?.slice(0, 12) || '—'}
                      </p>
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {selectedArtifact.artifactType === 'DRAWIO' && (
                        <button
                          type="button"
                          className="button tiny primary"
                          onClick={() => handleApplyToCanvas(selectedArtifact)}
                        >
                          应用到画布
                        </button>
                      )}
                      <button
                        type="button"
                        className="button tiny"
                        disabled={rollbackMutation.isPending}
                        onClick={() => {
                          if (window.confirm(`确定要回滚到版本 v${selectedArtifact.versionNo} 吗？`)) {
                            rollbackMutation.mutate(selectedArtifact);
                          }
                        }}
                      >
                        {rollbackMutation.isPending ? '回滚中…' : '回滚至此版本'}
                      </button>
                    </div>
                  </div>

                  {/* Branch creation */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', background: '#fffdf8', borderRadius: 8, border: '1px solid var(--line)' }}>
                    <input
                      style={{ flex: 1, padding: '6px 9px', fontSize: 12, border: '1px solid var(--line)', borderRadius: 6 }}
                      placeholder="输入新分支名 (如 feature-flow-v2)"
                      value={newBranchName}
                      onChange={(e) => setNewBranchName(e.target.value)}
                    />
                    <button
                      type="button"
                      className="button tiny"
                      disabled={branchMutation.isPending || !newBranchName.trim()}
                      onClick={handleCreateBranch}
                    >
                      {branchMutation.isPending ? '创建中…' : '基于此版本建分支'}
                    </button>
                  </div>
                  {branchError && <p className="tool-error" style={{ margin: 0, fontSize: 11 }}>{branchError}</p>}

                  {/* Same lineage diff */}
                  {lineageCandidates.length > 0 && (
                    <details className="artifact-diff-section" style={{ border: '1px solid var(--line)', borderRadius: 8, padding: '8px 12px', background: '#fffaf0' }}>
                      <summary style={{ fontSize: 13, fontWeight: 700, cursor: 'pointer' }}>
                        谱系差异比对 (Diff 与同 Lineage 历史版本)
                      </summary>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '8px 0', flexWrap: 'wrap' }}>
                        <span style={{ fontSize: 11, color: 'var(--muted)' }}>对比基准 Base:</span>
                        <select
                          value={effectiveDiffBaseId}
                          onChange={(e) => setDiffBaseId(e.target.value)}
                          style={{ padding: '4px 8px', fontSize: 12, border: '1px solid var(--line)', borderRadius: 6 }}
                        >
                          {lineageCandidates.map((c) => (
                            <option key={c.id} value={c.id}>
                              v{c.versionNo} ({c.branchName}) - {new Date(c.createdAt).toLocaleTimeString()}
                            </option>
                          ))}
                        </select>
                      </div>
                      {diffQuery.isLoading && <p style={{ fontSize: 11, color: 'var(--muted)' }}>正在计算差异…</p>}
                      {diffQuery.isError && (
                        <div className="monitor-detail-error" style={{ margin: '8px 0', fontSize: 11 }}>
                          计算差异失败：{diffQuery.error.message}
                          <button type="button" className="button tiny" style={{ marginLeft: 8 }} onClick={() => void diffQuery.refetch()}>重试</button>
                        </div>
                      )}
                      {diffQuery.data && (
                        <pre style={{ maxHeight: 200, overflow: 'auto', fontSize: 11, background: '#2f2923', color: '#fff4da', padding: 10, borderRadius: 6 }}>
                          {diffQuery.data}
                        </pre>
                      )}
                    </details>
                  )}

                  {/* Content viewer */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4, flex: 1, minHeight: 180 }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink)' }}>产物内容预览 ({selectedArtifact.mimeType})</span>
                    <pre style={{ flex: 1, maxHeight: 280, overflow: 'auto', fontSize: 11, padding: 10, borderRadius: 8, background: '#2f2923', color: '#fff4da' }}>
                      {selectedArtifact.content}
                    </pre>
                  </div>
                </>
              )}
            </section>
          </div>
        )}

        <footer className="modal-actions" style={{ marginTop: 14 }}>
          <button type="button" className="button" onClick={onClose}>关闭</button>
        </footer>
      </div>
    </div>
  );
}
