import { useState, type FormEvent, type KeyboardEvent } from 'react';

interface Props {
  busy: boolean;
  disabled?: boolean;
  onSend: (message: string) => Promise<void>;
  onStop: () => void;
  canResume?: boolean;
  onResume?: () => void;
}

export function PromptComposer({ busy, disabled, onSend, onStop, canResume, onResume }: Props) {
  const [message, setMessage] = useState('');
  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    const value = message.trim();
    if (!value || busy || disabled) return;
    setMessage('');
    await onSend(value);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
  };

  return (
    <form className="prompt-composer" onSubmit={submit}>
      <textarea value={message} disabled={disabled} onChange={(e) => setMessage(e.target.value)} onKeyDown={onKeyDown} placeholder="描述图表类型、节点、关系与布局要求…" rows={3} aria-label="绘图需求" />
      <div className="composer-footer">
        <span>Enter 发送 · Shift+Enter 换行</span>
        {busy ? (
          <button type="button" className="button danger" onClick={onStop}>停止接收</button>
        ) : canResume ? (
          <button type="button" className="button primary" disabled={disabled} onClick={onResume}>继续会话</button>
        ) : (
          <button type="submit" className="button primary" disabled={disabled || !message.trim()}>生成图表</button>
        )}
      </div>
    </form>
  );
}
