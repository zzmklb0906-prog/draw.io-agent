import { useEffect, useState } from 'react';
import {
  useModelSettingsStore,
  type ModelSettings,
} from '../model-settings.store';

interface Props {
  open: boolean;
  onClose: () => void;
}

export function ModelSettingsDialog({ open, onClose }: Props) {
  const current = useModelSettingsStore();
  const setSettings = useModelSettingsStore((state) => state.setSettings);
  const clear = useModelSettingsStore((state) => state.clear);
  const [draft, setDraft] = useState<ModelSettings>(current);

  useEffect(() => {
    if (open) setDraft(current);
  }, [open, current]);

  if (!open) return null;

  const update = (key: keyof ModelSettings, value: string | boolean) =>
    setDraft((state) => ({ ...state, [key]: value }));

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="model-settings-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <p className="eyebrow">高级设置</p>
            <h2 id="model-settings-title">自定义模型</h2>
          </div>
          <button className="icon-button" onClick={onClose} aria-label="关闭设置">×</button>
        </div>
        <p className="security-note">
          API Key 仅保存在当前页面内存中，刷新即清除。请勿在公共设备上输入生产密钥。
        </p>
        <label className="toggle-row">
          <input
            type="checkbox"
            checked={draft.enabled}
            onChange={(event) => update('enabled', event.target.checked)}
          />
          <span>为当前请求启用自定义模型配置</span>
        </label>
        <div className="form-grid">
          <label className="field">
            <span>Base URL</span>
            <input disabled={!draft.enabled} value={draft.customBaseUrl} onChange={(e) => update('customBaseUrl', e.target.value)} placeholder="https://api.example.com" />
          </label>
          <label className="field">
            <span>API Key</span>
            <input disabled={!draft.enabled} type="password" autoComplete="off" value={draft.customApiKey} onChange={(e) => update('customApiKey', e.target.value)} placeholder="仅在内存中保存" />
          </label>
          <label className="field">
            <span>Completions Path</span>
            <input disabled={!draft.enabled} value={draft.customCompletionsPath} onChange={(e) => update('customCompletionsPath', e.target.value)} placeholder="v1/chat/completions" />
          </label>
          <label className="field">
            <span>Model</span>
            <input disabled={!draft.enabled} value={draft.customModel} onChange={(e) => update('customModel', e.target.value)} placeholder="模型名称" />
          </label>
        </div>
        <div className="modal-actions">
          <button className="button ghost" onClick={() => { clear(); onClose(); }}>清除配置</button>
          <button className="button primary" onClick={() => { setSettings(draft); onClose(); }}>保存到当前页面</button>
        </div>
      </section>
    </div>
  );
}
