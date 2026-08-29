import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { submitCapabilityFeedback, type CapabilityFeedbackJudgment } from './monitor.api';
import type { CapabilityExecution, CapabilitySearch } from './monitor.types';

const duration = (ms: number) => ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
const size = (bytes: number) => bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KiB`;

export function CapabilityTrace({
  invocationId,
  searches,
  executions,
}: {
  invocationId?: string;
  searches: CapabilitySearch[];
  executions: CapabilityExecution[];
}) {
  const [activeFeedbackKey, setActiveFeedbackKey] = useState<string | null>(null);
  const [noteMap, setNoteMap] = useState<Record<string, string>>({});
  const [submittedMap, setSubmittedMap] = useState<Record<string, { judgment: CapabilityFeedbackJudgment; note?: string }>>({});

  const feedbackMutation = useMutation({
    mutationFn: async (variables: { searchId: string; capabilityId: string; judgment: CapabilityFeedbackJudgment; note?: string }) => {
      if (!invocationId) throw new Error('缺少 invocationId，无法提交反馈');
      await submitCapabilityFeedback(invocationId, variables);
      return variables;
    },
    onSuccess: (variables) => {
      const key = `${variables.searchId}-${variables.capabilityId}`;
      setSubmittedMap((prev) => ({ ...prev, [key]: { judgment: variables.judgment, note: variables.note } }));
      setActiveFeedbackKey(null);
    },
  });

  const handleFeedback = (searchId: string, capabilityId: string, judgment: CapabilityFeedbackJudgment) => {
    const key = `${searchId}-${capabilityId}`;
    const note = noteMap[key]?.trim();
    feedbackMutation.mutate({ searchId, capabilityId, judgment, note: note || undefined });
  };

  if (!searches.length && !executions.length) return <p className="monitor-empty compact">本次未触发动态能力检索。</p>;

  return (
    <div className="capability-trace">
      {searches.map((search) => (
        <article className="capability-search" key={search.id}>
          <header>
            <div>
              <strong>SEARCH · {search.query}</strong>
              <small>{search.agentName} · {search.resultCount}/{search.registrySize} 项 · {duration(search.durationMs)}</small>
            </div>
            <i className={`monitor-status ${search.status.toLowerCase()}`}>{search.status}</i>
          </header>
          <div className="capability-candidates">
            {search.candidates.map((candidate) => {
              const itemKey = `${search.id}-${candidate.capabilityId}`;
              const submitted = submittedMap[itemKey];
              const isEditingNote = activeFeedbackKey === itemKey;
              const isSubmittingThis = feedbackMutation.isPending && feedbackMutation.variables?.searchId === search.id && feedbackMutation.variables?.capabilityId === candidate.capabilityId;

              return (
                <div className={`capability-candidate-row ${candidate.selected ? 'selected' : ''}`} key={`${search.id}-${candidate.rank}`}>
                  <div className="capability-candidate-main">
                    <b>#{candidate.rank}</b>
                    <span>
                      <strong>{candidate.name}</strong>
                      <small>{candidate.type} · {candidate.group} · v{candidate.version}</small>
                    </span>
                    <em>{candidate.score.toFixed(2)}</em>
                    {candidate.selected && <i>SELECTED</i>}
                  </div>
                  {invocationId && (
                    <div className="capability-feedback-ctrl">
                      {submitted ? (
                        <span className="feedback-badge success">已反馈 {submitted.judgment === 'NO_IMPACT' ? '👍 有效' : '👎 不匹配'}</span>
                      ) : (
                        <>
                          <div className="feedback-buttons">
                            <button
                              type="button"
                              className="button tiny"
                              disabled={feedbackMutation.isPending}
                              onClick={() => handleFeedback(search.id, candidate.capabilityId, 'NO_IMPACT')}
                              title="标记该能力检索准确"
                            >
                              👍 有效
                            </button>
                            <button
                              type="button"
                              className="button tiny ghost"
                              disabled={feedbackMutation.isPending}
                              onClick={() => handleFeedback(search.id, candidate.capabilityId, 'WRONG_SELECTION')}
                              title="标记该能力不匹配或冗余"
                            >
                              👎 不匹配
                            </button>
                            <button
                              type="button"
                              className="button tiny text"
                              onClick={() => setActiveFeedbackKey((current) => (current === itemKey ? null : itemKey))}
                            >
                              {isEditingNote ? '收起备注' : '添加备注'}
                            </button>
                          </div>
                          {isEditingNote && (
                            <div className="feedback-note-input">
                              <input
                                placeholder="输入反馈原因（可选）"
                                value={noteMap[itemKey] || ''}
                                onChange={(e) => setNoteMap({ ...noteMap, [itemKey]: e.target.value })}
                                disabled={isSubmittingThis}
                              />
                            </div>
                          )}
                          {isSubmittingThis && <span className="feedback-loading">提交中…</span>}
                        </>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
          {feedbackMutation.error && feedbackMutation.variables?.searchId === search.id && (
            <p className="tool-error" style={{ margin: '8px 0 0' }}>反馈提交失败：{feedbackMutation.error.message}</p>
          )}
        </article>
      ))}
      {executions.map((execution) => (
        <article className={`capability-execution ${execution.status.toLowerCase()}`} key={execution.id}>
          <header>
            <div>
              <strong>{execution.action} · {execution.name}</strong>
              <small>{execution.type} · {execution.group} · v{execution.version} · {execution.riskLevel}</small>
            </div>
            <span>{duration(execution.durationMs)}</span>
            <i className={`monitor-status ${execution.status.toLowerCase()}`}>{execution.status}</i>
          </header>
          <dl>
            <div><dt>Capability ID</dt><dd>{execution.capabilityId}</dd></div>
            {execution.resourcePath && <div><dt>Resource</dt><dd>{execution.resourcePath}</dd></div>}
            <div><dt>结果</dt><dd>{size(execution.resultSize)} · SHA-256 {execution.resultHash.slice(0, 12) || '—'}</dd></div>
            {execution.artifactId && <div><dt>Artifact</dt><dd>{execution.artifactId}</dd></div>}
          </dl>
          <details>
            <summary>参数与结果摘要</summary>
            <pre>{JSON.stringify(execution.arguments, null, 2)}</pre>
            {execution.resultSummary && <pre>{execution.resultSummary}</pre>}
          </details>
          {execution.error && <p className="tool-error">{execution.error}</p>}
        </article>
      ))}
    </div>
  );
}
