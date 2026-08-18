import { useState } from 'react';
import type { ModelDecision } from './monitor.types';

interface ModelRoutingTraceProps {
  decisions: ModelDecision[];
}

export function ModelRoutingTrace({ decisions }: ModelRoutingTraceProps) {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(0);

  if (!decisions || decisions.length === 0) {
    return <p className="monitor-empty compact">本次未记录模型路由决策。</p>;
  }

  const getComplexityBadge = (complexity: number) => {
    switch (complexity) {
      case 3:
        return { label: 'L3 深度推理', color: 'badge-l3', desc: '处理复杂长文本、多分支结构与严格 XML 生成' };
      case 2:
        return { label: 'L2 标准平衡', color: 'badge-l2', desc: '处理常规多轮交互与意图改写' };
      case 1:
        return { label: 'L1 极速轻量', color: 'badge-l1', desc: '处理轻量指令解析与工具探测' };
      default:
        return { label: 'L0 默认/自定义', color: 'badge-l0', desc: '默认或用户显式指定' };
    }
  };

  return (
    <div className="model-routing-trace-container">
      {decisions.map((item, index) => {
        const badge = getComplexityBadge(item.complexity);
        const isExpanded = expandedIndex === index;
        const timeStr = item.timestamp ? new Date(item.timestamp).toLocaleTimeString('zh-CN') : '';
        const metrics = item.metrics || {};
        const reasoningScore = typeof metrics.finalReasoningScore === 'number' ? metrics.finalReasoningScore : (typeof metrics.reasoningScore === 'number' ? metrics.reasoningScore : undefined);

        return (
          <div key={`${item.timestamp}-${index}`} className={`routing-card ${isExpanded ? 'expanded' : ''}`}>
            <div className="routing-card-header" onClick={() => setExpandedIndex(isExpanded ? null : index)}>
              <div className="routing-card-main">
                <span className="routing-seq">#{index + 1}</span>
                <strong className="routing-model-name">{item.model || 'Agent 默认模型'}</strong>
                <span className="routing-agent-tag">{item.agentName}</span>
                <span className={`routing-badge ${badge.color}`}>{item.explicit ? '用户指定' : badge.label}</span>
              </div>
              <div className="routing-card-meta">
                <span className="routing-time">{timeStr}</span>
                <span className="routing-reason-preview">{item.reason}</span>
                <button className="button tiny outline">{isExpanded ? '收起依据' : '展开路由依据'}</button>
              </div>
            </div>

            {/* 路由决策摘要 */}
            {item.narrative && (
              <div className="routing-narrative">
                <div className="narrative-title">
                  <span className="narrative-icon">📋</span>
                  <b>路由决策摘要 (Routing Decision Summary)</b>
                </div>
                <p className="narrative-content">{item.narrative}</p>
              </div>
            )}

            {/* 展开的量化指标与决策链 */}
            {isExpanded && (
              <div className="routing-card-body">
                {/* 路由量化特征 */}
                <div className="routing-metrics-grid">
                  {typeof metrics.latestUserTextLength === 'number' && (
                    <div className="metric-item">
                      <span className="metric-label">当前用户消息长度</span>
                      <strong className="metric-val">{metrics.latestUserTextLength.toLocaleString()} 字符</strong>
                    </div>
                  )}
                  {/* Fallback for legacy textLength key */}
                  {typeof metrics.latestUserTextLength !== 'number' && typeof metrics.textLength === 'number' && (
                    <div className="metric-item">
                      <span className="metric-label">请求文本长度</span>
                      <strong className="metric-val">{metrics.textLength.toLocaleString()} 字符</strong>
                    </div>
                  )}
                  {typeof metrics.totalContextChars === 'number' && (
                    <div className="metric-item">
                      <span className="metric-label">上下文总长度</span>
                      <strong className="metric-val">{metrics.totalContextChars.toLocaleString()} 字符</strong>
                    </div>
                  )}
                  {reasoningScore !== undefined && (
                    <div className="metric-item">
                      <span className="metric-label">综合推理分 (阈值 0.45)</span>
                      <strong className={`metric-val ${reasoningScore > 0.45 ? 'high' : 'normal'}`}>
                        {reasoningScore.toFixed(3)}
                      </strong>
                    </div>
                  )}
                  {typeof metrics.lightweightScore === 'number' && (
                    <div className="metric-item">
                      <span className="metric-label">轻量特征分 (阈值 0.40)</span>
                      <strong className="metric-val">{metrics.lightweightScore.toFixed(3)}</strong>
                    </div>
                  )}
                  {typeof metrics.logLengthFactor === 'number' && (
                    <div className="metric-item">
                      <span className="metric-label">长度对数因子</span>
                      <strong className="metric-val">{metrics.logLengthFactor.toFixed(3)}</strong>
                    </div>
                  )}
                </div>

                {/* 匹配特征词 */}
                {item.matchedKeywords && item.matchedKeywords.length > 0 && (
                  <div className="routing-keywords-box">
                    <span className="box-title">🎯 匹配特征词 (Matched Keywords):</span>
                    <div className="keyword-tags">
                      {item.matchedKeywords.map((kw, kwIdx) => (
                        <span key={kwIdx} className="keyword-tag">{kw}</span>
                      ))}
                    </div>
                  </div>
                )}

                {/* 决策流水线 */}
                {item.pipelineTrail && item.pipelineTrail.length > 0 && (
                  <div className="routing-pipeline-box">
                    <span className="box-title">🛣️ 决策流水线 (Routing Pipeline):</span>
                    <div className="pipeline-steps">
                      {item.pipelineTrail.map((step, sIdx) => (
                        <div key={sIdx} className={`pipeline-step ${step.status.toLowerCase()}`}>
                          <span className={`step-dot ${step.status.toLowerCase()}`}/>
                          <div className="step-content">
                            <div className="step-header">
                              <strong>{step.tier}</strong>
                              <span className={`step-status-tag ${step.status.toLowerCase()}`}>
                                {step.status === 'HIT' ? '✔ 命中决断' : (step.status === 'PASSED' ? '➜ 移交下一级' : step.status)}
                              </span>
                            </div>
                            {step.detail && <p className="step-detail">{step.detail}</p>}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
