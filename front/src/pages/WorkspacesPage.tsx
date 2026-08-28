import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../features/auth/auth.store';
import {
  createWorkspace,
  deleteWorkspaceMember,
  putWorkspaceMember,
  queryWorkspaceMembers,
  queryWorkspaces,
} from '../features/workspaces/workspaces.api';
import type { WorkspaceItem, WorkspaceRole } from '../features/workspaces/workspaces.types';

export function WorkspacesPage() {
  const currentUser = useAuthStore((state) => state.user)!;
  const client = useQueryClient();

  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string>('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newWorkspaceName, setNewWorkspaceName] = useState('');
  const [newWorkspaceDesc, setNewWorkspaceDesc] = useState('');
  const [createError, setCreateError] = useState('');

  // Add / Edit Member form states
  const [memberUsername, setMemberUsername] = useState('');
  const [memberRole, setMemberRole] = useState<WorkspaceRole>('VIEWER');
  const [memberActionError, setMemberActionError] = useState('');

  const workspacesQuery = useQuery({
    queryKey: ['workspaces'],
    queryFn: queryWorkspaces,
  });

  const workspaces = useMemo(() => workspacesQuery.data ?? [], [workspacesQuery.data]);

  const activeWorkspace: WorkspaceItem | null = useMemo(() => {
    if (!workspaces.length) return null;
    return workspaces.find((w) => w.id === selectedWorkspaceId) || workspaces[0];
  }, [workspaces, selectedWorkspaceId]);

  const currentWorkspaceId = activeWorkspace?.id || '';
  const isOwner = activeWorkspace?.role === 'OWNER';

  const membersQuery = useQuery({
    queryKey: ['workspace-members', currentWorkspaceId],
    queryFn: () => queryWorkspaceMembers(currentWorkspaceId),
    enabled: !!currentWorkspaceId,
  });

  const members = useMemo(() => membersQuery.data ?? [], [membersQuery.data]);

  const createWorkspaceMutation = useMutation({
    mutationFn: async () => {
      const name = newWorkspaceName.trim();
      if (!name || name.length > 160) {
        throw new Error('工作区名称不能为空且不能超过 160 个字符');
      }
      return createWorkspace({ name, description: newWorkspaceDesc.trim() || undefined });
    },
    onSuccess: (newWs) => {
      setShowCreateModal(false);
      setNewWorkspaceName('');
      setNewWorkspaceDesc('');
      setCreateError('');
      if (newWs?.id) {
        setSelectedWorkspaceId(newWs.id);
      }
      void client.invalidateQueries({ queryKey: ['workspaces'] });
    },
    onError: (err) => {
      setCreateError(err.message);
    },
  });

  const putMemberMutation = useMutation({
    mutationFn: async () => {
      const targetUser = memberUsername.trim();
      if (!targetUser) throw new Error('成员用户名不能为空');
      return putWorkspaceMember(currentWorkspaceId, {
        username: targetUser,
        role: memberRole,
      });
    },
    onSuccess: () => {
      setMemberUsername('');
      setMemberRole('VIEWER');
      setMemberActionError('');
      void client.invalidateQueries({ queryKey: ['workspace-members', currentWorkspaceId] });
    },
    onError: (err) => {
      setMemberActionError(err.message);
    },
  });

  const removeMemberMutation = useMutation({
    mutationFn: async (targetUsername: string) => {
      return deleteWorkspaceMember(currentWorkspaceId, targetUsername);
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workspace-members', currentWorkspaceId] });
    },
    onError: (err) => {
      setMemberActionError(err.message);
    },
  });

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createWorkspaceMutation.mutate();
  };

  const handlePutMemberSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    putMemberMutation.mutate();
  };

  const handleRemoveMember = (targetUsername: string) => {
    if (targetUsername === currentUser.username) {
      alert('无法在成员列表中移除当前登录账号本人。');
      return;
    }
    if (window.confirm(`确定要从当前工作区中移除成员「${targetUsername}」吗？`)) {
      removeMemberMutation.mutate(targetUsername);
    }
  };

  return (
    <main className="agent-console">
      <header className="monitor-header">
        <div>
          <p className="eyebrow">COLLABORATION & ACCESS</p>
          <h1>团队与工作区管理</h1>
          <p>
            管理多工作区团队成员与访问权限 (OWNER / EDITOR / VIEWER)。注：此页面用于管理协作范围与人员权限，会话创建独立运行。
          </p>
        </div>
        <nav>
          <button type="button" className="button primary" onClick={() => setShowCreateModal(true)}>
            + 新建工作区
          </button>
          <Link className="button" to="/workspace">
            返回绘图台
          </Link>
        </nav>
      </header>

      {workspacesQuery.isLoading ? (
        <p className="monitor-empty">正在加载工作区列表…</p>
      ) : workspacesQuery.isError ? (
        <div className="monitor-detail-error" style={{ maxWidth: 1440, margin: '14px auto' }}>
          加载工作区失败：{workspacesQuery.error.message}
          <button type="button" className="button tiny" style={{ marginLeft: 8 }} onClick={() => void workspacesQuery.refetch()}>
            重试
          </button>
        </div>
      ) : workspaces.length === 0 ? (
        <div className="monitor-empty" style={{ textAlign: 'center', padding: 40 }}>
          <p>您当前尚未加入或创建任何工作区。</p>
          <button type="button" className="button primary" onClick={() => setShowCreateModal(true)}>
            创建第一个工作区
          </button>
        </div>
      ) : (
        <div className="agent-console-grid" style={{ marginTop: 16 }}>
          {/* Left workspace list */}
          <aside className="task-tree" style={{ borderRight: '1px solid var(--line)' }}>
            <h2>我的工作区</h2>
            {workspaces.map((w) => {
              const isSelected = w.id === currentWorkspaceId;
              return (
                <button
                  key={w.id}
                  type="button"
                  className={`subagent-row ${isSelected ? 'active' : ''}`}
                  onClick={() => {
                    setSelectedWorkspaceId(w.id);
                    setMemberActionError('');
                  }}
                >
                  <strong>{w.name}</strong>
                  <span className={`task-status ${w.role === 'OWNER' ? 'completed' : 'waiting_approval'}`}>
                    {w.role}
                  </span>
                  {w.description && <small>{w.description}</small>}
                </button>
              );
            })}
          </aside>

          {/* Right workspace details and member management */}
          <section className="task-detail" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {activeWorkspace && (
              <>
                <div className="task-title">
                  <div>
                    <h2>{activeWorkspace.name}</h2>
                    <p style={{ color: 'var(--muted)', fontSize: 12, margin: '4px 0 0' }}>
                      工作区 ID: {activeWorkspace.id} | 我的角色: <b>{activeWorkspace.role}</b>
                      {activeWorkspace.description ? ` | ${activeWorkspace.description}` : ''}
                    </p>
                  </div>
                </div>

                <div className="task-facts">
                  <div>
                    <dt>创建时间</dt>
                    <dd>{new Date(activeWorkspace.createdAt).toLocaleString('zh-CN')}</dd>
                  </div>
                  <div>
                    <dt>权限控制模式</dt>
                    <dd>{isOwner ? '所有者 (可管理成员与授权)' : '协作成员 (只读查看)'}</dd>
                  </div>
                </div>

                {/* Member management panel */}
                <div>
                  <h3 style={{ fontSize: 17, margin: '0 0 8px' }}>成员列表 ({members.length})</h3>

                  {isOwner && (
                    <form
                      onSubmit={handlePutMemberSubmit}
                      style={{
                        display: 'flex',
                        gap: 8,
                        alignItems: 'center',
                        flexWrap: 'wrap',
                        padding: 12,
                        background: '#fffaf0',
                        borderRadius: 10,
                        border: '1px solid var(--line)',
                        marginBottom: 12,
                      }}
                    >
                      <input
                        style={{ flex: 1, minWidth: 160, padding: '7px 10px', fontSize: 12, border: '1px solid var(--line)', borderRadius: 6 }}
                        placeholder="输入成员账号 (username)"
                        value={memberUsername}
                        onChange={(e) => setMemberUsername(e.target.value)}
                        required
                      />
                      <select
                        style={{ padding: '7px 10px', fontSize: 12, border: '1px solid var(--line)', borderRadius: 6 }}
                        value={memberRole}
                        onChange={(e) => setMemberRole(e.target.value as WorkspaceRole)}
                      >
                        <option value="VIEWER">VIEWER (查看者)</option>
                        <option value="EDITOR">EDITOR (协作者)</option>
                        <option value="OWNER">OWNER (所有者)</option>
                      </select>
                      <button
                        type="submit"
                        className="button tiny primary"
                        disabled={putMemberMutation.isPending || !memberUsername.trim()}
                      >
                        {putMemberMutation.isPending ? '保存中…' : '添加 / 更新成员'}
                      </button>
                    </form>
                  )}

                  {memberActionError && (
                    <div className="monitor-detail-error" style={{ marginBottom: 12 }}>
                      {memberActionError}
                    </div>
                  )}

                  {membersQuery.isLoading ? (
                    <p style={{ fontSize: 12, color: 'var(--muted)' }}>正在拉取成员列表…</p>
                  ) : membersQuery.isError ? (
                    <div className="monitor-detail-error">
                      拉取成员列表失败：{membersQuery.error.message}
                      <button type="button" className="button tiny" style={{ marginLeft: 8 }} onClick={() => void membersQuery.refetch()}>
                        重试
                      </button>
                    </div>
                  ) : members.length === 0 ? (
                    <p className="monitor-empty">该工作区暂无其他成员。</p>
                  ) : (
                    <div style={{ display: 'grid', gap: 8 }}>
                      {members.map((m) => {
                        const isSelf = m.username === currentUser.username;
                        const isTargetOwner = m.role === 'OWNER';
                        const canDelete = isOwner && !isSelf && !isTargetOwner;

                        return (
                          <div
                            key={m.username}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between',
                              padding: '10px 14px',
                              background: '#fffdf8',
                              borderRadius: 8,
                              border: '1px solid var(--line)',
                            }}
                          >
                            <div style={{ display: 'grid', gap: 2 }}>
                              <strong style={{ fontSize: 13 }}>
                                {m.username} {m.displayName ? `(${m.displayName})` : ''} {isSelf ? ' [当前账号]' : ''}
                              </strong>
                              <small style={{ color: 'var(--muted)', fontSize: 11 }}>
                                加入时间: {new Date(m.createdAt).toLocaleString('zh-CN')}
                              </small>
                            </div>

                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span className={`task-status ${m.role === 'OWNER' ? 'completed' : m.role === 'EDITOR' ? 'running' : 'waiting_human'}`}>
                                {m.role}
                              </span>
                              {canDelete && (
                                <button
                                  type="button"
                                  className="button tiny danger"
                                  disabled={removeMemberMutation.isPending}
                                  onClick={() => handleRemoveMember(m.username)}
                                >
                                  移除
                                </button>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </>
            )}
          </section>
        </div>
      )}

      {/* Create Workspace Modal */}
      {showCreateModal && (
        <div className="modal-backdrop">
          <div className="modal-card" style={{ width: 'min(500px, 95%)' }}>
            <header className="modal-header">
              <h2>新建工作区</h2>
              <button type="button" className="button ghost" onClick={() => setShowCreateModal(false)}>✕</button>
            </header>
            <form onSubmit={handleCreateSubmit} style={{ display: 'grid', gap: 12, marginTop: 12 }}>
              <label className="field">
                <span>工作区名称 (1-160 字符)</span>
                <input
                  value={newWorkspaceName}
                  onChange={(e) => setNewWorkspaceName(e.target.value)}
                  placeholder="例如: 智能运维图表小组"
                  maxLength={160}
                  required
                />
              </label>
              <label className="field">
                <span>用途与说明 (可选)</span>
                <textarea
                  value={newWorkspaceDesc}
                  onChange={(e) => setNewWorkspaceDesc(e.target.value)}
                  placeholder="描述该工作区的用途与协作人员"
                  style={{ minHeight: 80 }}
                />
              </label>
              {createError && <p className="tool-error">{createError}</p>}
              <footer className="modal-actions">
                <button type="button" className="button" onClick={() => setShowCreateModal(false)}>
                  取消
                </button>
                <button
                  type="submit"
                  className="button primary"
                  disabled={createWorkspaceMutation.isPending || !newWorkspaceName.trim()}
                >
                  {createWorkspaceMutation.isPending ? '创建中…' : '创建'}
                </button>
              </footer>
            </form>
          </div>
        </div>
      )}
    </main>
  );
}
