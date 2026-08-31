import { useEffect, useRef, useState, type ComponentPropsWithoutRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage } from '../chat.types';

const markdownComponents = {
  a: ({ children, ...props }: ComponentPropsWithoutRef<'a'>) => (
    <a {...props} target="_blank" rel="noreferrer noopener">{children}</a>
  ),
  table: ({ children, ...props }: ComponentPropsWithoutRef<'table'>) => (
    <div className="markdown-table-wrap" role="region" aria-label="表格内容" tabIndex={0}>
      <table {...props}>{children}</table>
    </div>
  ),
};

interface MessageListProps {
  messages: ChatMessage[];
  busy?: boolean;
  onApprove?: (approval: NonNullable<ChatMessage['approval']>) => Promise<void>;
  onRevise?: (approval: NonNullable<ChatMessage['approval']>, revision: string) => Promise<void>;
  onToolDecision?:(approval:NonNullable<ChatMessage['toolApproval']>,confirmed:boolean)=>Promise<void>;
}

function ApprovalCard({ message, busy, onApprove, onRevise }: {
  message: ChatMessage;
  busy: boolean;
  onApprove?: (approval: NonNullable<ChatMessage['approval']>) => Promise<void>;
  onRevise?: (approval: NonNullable<ChatMessage['approval']>, revision: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [revision, setRevision] = useState('');
  const approval = message.approval;
  if (!approval) return null;
  const decisionStatus = approval.decisionStatus ?? 'PENDING';
  const handled = decisionStatus !== 'PENDING';
  const statusLabel = decisionStatus === 'APPROVED' ? '已批准，正在绘图'
    : decisionStatus === 'REVISED' ? '已提交修改'
      : decisionStatus === 'COMPLETED' ? '已批准并完成' : '等待审核';

  const submitRevision = async () => {
    const value = revision.trim();
    if (!value || busy || !onRevise) return;
    setEditing(false);
    setRevision('');
    await onRevise(approval, value);
  };

  const scope = Array.isArray(approval.scope) ? approval.scope : [];
  const assumptions = Array.isArray(approval.assumptions) ? approval.assumptions : [];
  const questions = Array.isArray(approval.questions) ? approval.questions : [];

  return (
    <section className="approval-card" aria-label="绘图方案审核">
      <div className="approval-heading">
        <span>{statusLabel}</span>
        <strong>{approval.title || '绘图方案'}</strong>
      </div>
      <p className="approval-prompt">{approval.rewrittenPrompt || ''}</p>
      <dl className="approval-meta">
        <div><dt>图表类型</dt><dd>{approval.diagramType || '未指定'}</dd></div>
        {!!scope.length && <div><dt>范围</dt><dd>{scope.join(' · ')}</dd></div>}
      </dl>
      {!!assumptions.length && (
        <div className="approval-list"><strong>当前假设</strong><ul>{assumptions.map((item, idx) => <li key={idx}>{typeof item === 'string' ? item : JSON.stringify(item)}</li>)}</ul></div>
      )}
      {!!questions.length && (
        <div className="approval-list questions"><strong>需要确认</strong><ul>{questions.map((item, idx) => <li key={idx}>{typeof item === 'string' ? item : JSON.stringify(item)}</li>)}</ul></div>
      )}
      {editing && !handled && (
        <div className="approval-revision">
          <textarea value={revision} onChange={(event) => setRevision(event.target.value)} placeholder="说明需要增加、删除或调整的内容…" rows={3} autoFocus />
          <button className="button primary" disabled={!revision.trim() || busy} onClick={() => void submitRevision()}>提交修改</button>
        </div>
      )}
      <div className="approval-actions">
        <button className="button primary" disabled={busy || handled || !!questions.length} onClick={() => void onApprove?.(approval)}>{decisionStatus === 'COMPLETED' ? '绘图已完成' : handled ? '方案已处理' : '确认并开始绘图'}</button>
        <button className="button" disabled={busy || handled} onClick={() => setEditing((value) => !value)}>{handled ? '不可重复修改' : editing ? '收起修改' : '修改方案'}</button>
      </div>
    </section>
  );
}

export function MessageList({ messages, busy = false, onApprove, onRevise,onToolDecision }: MessageListProps) {
  const endRef = useRef<HTMLDivElement>(null);
  const latestApprovalId = [...messages].reverse().find((message) => message.approval && (message.approval.decisionStatus ?? 'PENDING') === 'PENDING')?.id;
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (!messages.length) {
    return (
      <div className="chat-empty">
        <span className="empty-mark">AI</span>
        <h2>描述你想绘制的图</h2>
        <p>例如：绘制一个包含网关、认证服务和订单服务的微服务架构图。</p>
      </div>
    );
  }

  return (
    <div className="message-list" aria-live="polite">
      {messages.map((message) => (
        <article key={message.id} className={`message ${message.role}`}>
          <span className="message-role">{message.role === 'user' ? '你' : message.role === 'assistant' ? 'AI' : '系统'}</span>
          {message.toolApproval ? <section className="approval-card" aria-label="高风险工具审批"><div className="approval-heading"><span>{message.toolApproval.decisionStatus==='PENDING'?'等待批准':message.toolApproval.decisionStatus==='APPROVED'?'已批准':message.toolApproval.decisionStatus==='DENIED'?'已拒绝':'已处理'}</span><strong>高风险工具执行请求</strong></div><pre className="approval-prompt">{JSON.stringify(message.toolApproval.details,null,2)}</pre><div className="approval-actions"><button className="button primary" disabled={busy||message.toolApproval.decisionStatus!=='PENDING'} onClick={()=>void onToolDecision?.(message.toolApproval!,true)}>批准执行</button><button className="button" disabled={busy||message.toolApproval.decisionStatus!=='PENDING'} onClick={()=>void onToolDecision?.(message.toolApproval!,false)}>拒绝</button></div></section> : message.approval ? (
            <ApprovalCard
              message={message}
              busy={busy || message.id !== latestApprovalId}
              onApprove={message.id === latestApprovalId ? onApprove : undefined}
              onRevise={message.id === latestApprovalId ? onRevise : undefined}
            />
          ) : message.role === 'assistant' ? (
            <div className="markdown-body">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                {message.content}
              </ReactMarkdown>
            </div>
          ) : <p>{message.content}</p>}
        </article>
      ))}
      <div ref={endRef} />
    </div>
  );
}
