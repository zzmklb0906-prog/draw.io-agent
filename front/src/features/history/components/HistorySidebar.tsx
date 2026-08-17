import type { ConversationSnapshot } from '../history.types';

interface Props {
  conversations: ConversationSnapshot[];
  activeId: string;
  onNew: () => void;
  onSelect: (conversation: ConversationSnapshot) => void;
  onRemove: (id: string) => void;
  collapsed: boolean;
  onToggle: () => void;
}

const formatter = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

export function HistorySidebar({ conversations, activeId, onNew, onSelect, onRemove, collapsed, onToggle }: Props) {
  return (
    <aside className={`history-panel ${collapsed ? 'collapsed' : ''}`}>
      <div className="history-heading">
        {!collapsed && <div><p className="eyebrow">HISTORY</p><h1>会话记录</h1></div>}
        <button className="history-toggle" onClick={onToggle} title={collapsed ? '展开历史会话' : '收起历史会话'}>{collapsed ? '›' : '‹'}</button>
        <button className="history-new" onClick={onNew} title="新建会话">＋</button>
      </div>
      {!collapsed && <p className="history-note">会话、任务状态与消息已持久化到服务端。</p>}
      <div className="history-list">
        {!conversations.length && <div className="history-empty">暂无会话。<br />新建会话后会显示在这里。</div>}
        {conversations.map((item) => (
          <article key={item.id} className={`history-item ${item.id === activeId ? 'active' : ''}`}>
            <button className="history-open" onClick={() => onSelect(item)}>
              {collapsed ? <i className="history-collapsed-marker" title={item.title} aria-label={item.title} /> : <strong title={item.title}>{item.title}</strong>}
              {!collapsed && <><span>{formatter.format(item.updatedAt)} · {item.messageCount ?? item.messages.length} 条消息</span><span className={`history-status ${item.workflowStatus?.toLowerCase()}`}>{item.phase || item.workflowStatus || 'IDLE'}{item.activeTool ? ` · ${item.activeTool.name} · ${((item.activeTool.durationMs ?? Date.now() - item.activeTool.startedAt) / 1000).toFixed(1)}s` : ''}</span></>}
            </button>
            {!collapsed && <button className="history-remove" onClick={() => onRemove(item.id)} aria-label={`删除 ${item.title}`}>×</button>}
          </article>
        ))}
      </div>
    </aside>
  );
}
