import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentSelector } from '../features/agents/components/AgentSelector';
import { queryAgents } from '../features/agents/agents.api';
import { useAuthStore } from '../features/auth/auth.store';
import { createSession, pauseWorkflow, queryWorkflow } from '../features/chat/chat.api';
import { streamChat } from '../features/chat/chat.stream';
import { useChatStore } from '../features/chat/chat.store';
import type { ChatRequest, StreamEnvelope } from '../features/chat/chat.types';
import { AgentProgress } from '../features/chat/components/AgentProgress';
import { MessageList } from '../features/chat/components/MessageList';
import { PromptComposer } from '../features/chat/components/PromptComposer';
import { DiagramToolbar } from '../features/diagram/components/DiagramToolbar';
import { DrawioEditor } from '../features/diagram/components/DrawioEditor';
import { useDiagramStore } from '../features/diagram/diagram.store';
import { buildDraftDiagram } from '../features/diagram/diagramXml';
import { useDiagramToolbar } from '../features/diagram/useDiagramToolbar';
import { HistorySidebar } from '../features/history/components/HistorySidebar';
import { useHistoryStore } from '../features/history/history.store';
import type { ConversationSnapshot } from '../features/history/history.types';
import { deleteConversation, queryConversation, queryConversations, type ServerConversation } from '../features/history/history.api';
import { ModelSettingsDialog } from '../features/settings/components/ModelSettingsDialog';
import { useModelSettingsStore } from '../features/settings/model-settings.store';

export function WorkspacePage() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user)!;
  const logout = useAuthStore((state) => state.logout);
  const chat = useChatStore();
  const diagram = useDiagramStore();
  const modelSettings = useModelSettingsStore();
  const history = useHistoryStore();
  const { editorRef, exportData } = useDiagramToolbar();
  const abortRef = useRef<AbortController | null>(null);
  const draftCellsRef = useRef(new Map<string, string>());
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [panel, setPanel] = useState<'history' | 'chat' | 'diagram'>('chat');
  const [notice, setNotice] = useState('');
  const [historyCollapsed, setHistoryCollapsed] = useState(() => localStorage.getItem('history-sidebar-collapsed') === 'true');
  const [chatWidth, setChatWidth] = useState(() => Number(localStorage.getItem('chat-panel-width') || 460));

  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: queryAgents });
  const conversationsQuery = useQuery({ queryKey: ['conversations'], queryFn: queryConversations });
  const refetchConversations = conversationsQuery.refetch;
  const isDrawAgent = chat.agentId === '300000';
  const serverSnapshots: ConversationSnapshot[] = (conversationsQuery.data ?? []).map((item) => {
    const cached = history.conversations.find((local) => local.sessionId === item.sessionId);
    return { id:item.id,title:item.title,agentId:item.agentId,sessionId:item.sessionId,messages:[],messageCount:item.messageCount,currentXml:cached?.currentXml ?? '',lastAiXml:cached?.lastAiXml ?? '',diagramTitle:cached?.diagramTitle ?? '',checkpointId:item.checkpointId??cached?.checkpointId,checkpointRevision:item.checkpointRevision??cached?.checkpointRevision,workflowStatus:(item.status as ConversationSnapshot['workflowStatus']) ?? 'IDLE',phase:item.status,activeTool:item.activeToolName&&item.activeToolStatus==='RUNNING'?{name:item.activeToolName,startedAt:new Date(item.activeToolStartedAt ?? item.updatedAt).getTime(),status:'RUNNING'}:undefined,createdAt:new Date(item.createdAt).getTime(),updatedAt:new Date(item.updatedAt).getTime() };
  });

  useEffect(() => {
    const agents = agentsQuery.data ?? [];
    if (!agents.length || chat.agentId) return;
    const preferred = agents.find((agent) => agent.agentId === '300000') ?? agents[0];
    chat.setAgent(preferred.agentId);
  }, [agentsQuery.data, chat]);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const saveSnapshot = useCallback(() => {
    const chatState = useChatStore.getState();
    if (!chatState.messages.length) return;
    const diagramState = useDiagramStore.getState();
    const historyState = useHistoryStore.getState();
    historyState.save({
      agentId: chatState.agentId,
      sessionId: chatState.sessionId,
      messages: chatState.messages,
      currentXml: diagramState.currentXml,
      lastAiXml: diagramState.lastAiXml,
      diagramTitle: diagramState.title,
      checkpointId: chatState.checkpointId || undefined,
      checkpointRevision: chatState.checkpointRevision || undefined,
      workflowStatus: chatState.workflowStatus,
      phase: chatState.statusText || chatState.phase,
      activeTool: chatState.toolRuns.find((tool) => tool.status === 'RUNNING') ?? chatState.toolRuns.at(-1),
    }, historyState.activeId || undefined);
  }, []);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    const state = useChatStore.getState();
    if (state.checkpointId) void pauseWorkflow(state.checkpointId)
      .then((checkpoint) => { state.setCheckpoint(checkpoint.checkpointId, checkpoint.revision, checkpoint.status); saveSnapshot(); })
      .catch(() => undefined);
    chat.finishRequest('error');
    if (state.checkpointId) chat.setCheckpoint(state.checkpointId, state.checkpointRevision, 'PAUSED');
    chat.setProgress('idle', '已持久化暂停，可从审核卡片继续。');
  }, [chat, saveSnapshot]);

  useEffect(() => {
    if (!chat.messages.length) return;
    const timer = window.setTimeout(saveSnapshot, 800);
    return () => window.clearTimeout(timer);
  }, [chat.messages, chat.sessionId, chat.phase, chat.statusText, chat.toolRuns, chat.checkpointId, chat.checkpointRevision, diagram.currentXml, diagram.lastAiXml, diagram.title, saveSnapshot]);

  const newSession = useCallback(async () => {
    if (!chat.agentId) return '';
    abortRef.current?.abort();
    saveSnapshot();
    chat.resetSession();
    diagram.clear();
    useHistoryStore.getState().setActive('');
    draftCellsRef.current.clear();
    const result = await createSession(chat.agentId, user.username, crypto.randomUUID());
    chat.setSession(result.sessionId, result.conversationId);
    await refetchConversations();
    setNotice('已创建新会话');
    return result.sessionId;
  }, [chat, diagram, refetchConversations, saveSnapshot, user.username]);

  const requestNewSession = useCallback(async () => {
    try {
      await newSession();
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '创建会话失败');
    }
  }, [newSession]);

  const changeAgent = async (agentId: string) => {
    abortRef.current?.abort();
    saveSnapshot();
    chat.setAgent(agentId);
    chat.resetSession();
    diagram.clear();
    history.setActive('');
    setNotice('智能体已切换，请开始新任务。');
  };

  const handleEvent = (requestId: string, event: StreamEnvelope) => {
    if (useChatStore.getState().activeRequestId !== requestId) return;
    const { chunk } = event;
    chat.setProgress(event.phase, event.phase);
    switch (chunk.type) {
      case 'token': chat.appendAssistant(chunk.content); break;
      case 'status': chat.setProgress(event.phase, chunk.content); break;
      case 'tool': chat.updateTool(chunk); chat.setProgress(event.phase, chunk.status === 'RUNNING' ? `正在调用 ${chunk.name}` : `${chunk.name} ${chunk.status === 'SUCCESS' ? '已完成' : '失败'}`); break;
      case 'checkpoint': chat.setCheckpoint(chunk.checkpointId, chunk.revision, chunk.status as typeof chat.workflowStatus); chat.setProgress(event.phase, `${chunk.status} · Checkpoint ${chunk.checkpointId.slice(0, 8)}`); break;
      case 'user': chat.addMessage('assistant', chunk.content); break;
      case 'approval': chat.addApproval(chunk); setPanel('chat'); break;
      case 'tool_approval': chat.addToolApproval(chunk); setPanel('chat'); break;
      case 'drawio_node':
        chat.addNode();
        draftCellsRef.current.set(`node:${chunk.id}`, chunk.xml);
        diagram.applyDraftXml(buildDraftDiagram(draftCellsRef.current.values()));
        setPanel('diagram');
        break;
      case 'drawio_edge':
        chat.addEdge();
        draftCellsRef.current.set(`edge:${chunk.id}`, chunk.xml);
        diagram.applyDraftXml(buildDraftDiagram(draftCellsRef.current.values()));
        setPanel('diagram');
        break;
      case 'drawio_done':
      case 'drawio':
        if (diagram.applyAiXml(chunk.content)) {
          draftCellsRef.current.clear();
          const state = useChatStore.getState();
          if (state.checkpointId) state.setApprovalDecision(state.checkpointId, 'COMPLETED');
          state.addMessage('assistant', `图表已生成完成，包含 ${state.nodeCount} 个节点和 ${state.edgeCount} 条连线。你可以在中间画布继续编辑，或使用工具栏导出结果。`);
          setPanel('diagram');
        }
        break;
      case 'ppt_raw': chat.setProgress('generating', '当前工作台暂不支持 PPT 画布。'); break;
      case 'error': chat.addMessage('system', chunk.content); chat.finishRequest('error'); break;
      case 'done': chat.finishRequest('done'); break;
    }
  };

  const send = async (message: string, resume?: { checkpointId: string; revision: number; decision: 'APPROVE' | 'REVISE' | 'CONTINUE' | 'TOOL_APPROVE' | 'TOOL_DENY';toolCallId?:string;confirmed?:boolean;payload?:Record<string,unknown> }) => {
    setNotice('');
    let sessionId = chat.sessionId;
    let requestId = '';
    try {
      if (resume?.decision === 'APPROVE') chat.setApprovalDecision(resume.checkpointId, 'APPROVED');
      if (resume?.decision === 'REVISE') chat.setApprovalDecision(resume.checkpointId, 'REVISED');
      if (!sessionId) sessionId = await newSession();
      if (!sessionId) throw new Error('无法创建会话');
      const activeConversationId=useChatStore.getState().conversationId;
      if (message.trim()) chat.addMessage('user', message);
      saveSnapshot();
      requestId = crypto.randomUUID();
      chat.beginRequest(requestId);
      draftCellsRef.current.clear();
      const controller = new AbortController();
      abortRef.current = controller;
      const payload: ChatRequest = {
        agentId: chat.agentId,
        userId: user.username,
        sessionId,
        conversationId: activeConversationId,
        message,
        idempotencyKey: requestId,
        ...(resume ? { checkpointId: resume.checkpointId, checkpointRevision: resume.revision, checkpointDecision: resume.decision } : {}),
        ...(resume?.toolCallId?{toolConfirmationCallId:resume.toolCallId,toolConfirmed:resume.confirmed,toolConfirmationPayload:resume.payload??{}}:{}),
        ...(modelSettings.enabled
          ? {
              customBaseUrl: modelSettings.customBaseUrl || undefined,
              customApiKey: modelSettings.customApiKey || undefined,
              customCompletionsPath: modelSettings.customCompletionsPath || undefined,
              customModel: modelSettings.customModel || undefined,
            }
          : {}),
      };
      await streamChat(payload, controller.signal, (event) => handleEvent(requestId, event), () => {
        chat.setProgress('thinking', '收到一条无法解析的流式数据，已跳过。');
      });
      if (useChatStore.getState().activeRequestId === requestId) chat.finishRequest('done');
      saveSnapshot();
      await conversationsQuery.refetch();
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        const message = error instanceof Error ? error.message : '请求失败';
        chat.addMessage('system', message);
        if (resume?.checkpointId && (resume.decision === 'APPROVE' || resume.decision === 'REVISE')) {
          chat.setApprovalDecision(resume.checkpointId, 'PENDING');
        }
        if(resume?.toolCallId&&(resume.decision==='TOOL_APPROVE'||resume.decision==='TOOL_DENY'))chat.setToolApprovalDecision(resume.toolCallId,'PENDING');
        if (requestId && useChatStore.getState().activeRequestId === requestId) chat.finishRequest('error');
        saveSnapshot();
      }
    } finally {
      // Network cancellation, Vite HMR and an HTTP stream that closes without a final
      // `done` frame must never leave the approval controls permanently disabled.
      if (requestId && useChatStore.getState().activeRequestId === requestId) chat.finishRequest('done');
      abortRef.current = null;
    }
  };

  const restoreConversation = async (conversation: ConversationSnapshot) => {
    abortRef.current?.abort();
    saveSnapshot();
    const remote:ServerConversation=await queryConversation(conversation.id);
    let restoredXml='';
    const remoteMessages=remote.messages.map((message)=>{
      let approval;
      let toolApproval;
      if(message.type==='APPROVAL'&&message.contentJson){try{approval={...(JSON.parse(message.contentJson)),decisionStatus:conversation.workflowStatus==='WAITING_APPROVAL'?'PENDING':'COMPLETED'};}catch{approval=undefined;}}
      if(message.type==='TOOL_APPROVAL'&&message.contentJson){try{toolApproval={...(JSON.parse(message.contentJson)),decisionStatus:conversation.workflowStatus==='WAITING_TOOL_APPROVAL'?'PENDING':'HANDLED'};}catch{toolApproval=undefined;}}
      if(message.type==='DRAWIO'&&message.contentJson){try{restoredXml=JSON.parse(message.contentJson).xml ?? restoredXml;}catch{/* malformed historical payload */}}
      let content=message.content ?? '';
      if(content.includes('[APPROVED_DRAWING_BRIEF]')){
        for(const line of content.split(/\r?\n/)){const start=line.indexOf('{"type"');if(start<0)continue;try{const structured=JSON.parse(line.slice(start));if((structured.type==='drawio_done'||structured.type==='drawio')&&structured.content)restoredXml=structured.content;}catch{/* legacy partial JSON */}}
        content=content.split(/\r?\n/).filter((line)=>!line.trim().startsWith('[APPROVED_DRAWING_BRIEF]')&&!line.trim().startsWith('{"type":"drawio_')).join('\n').trim();
        if(!content&&restoredXml)content='图表已生成完成，可在画布中继续编辑或导出。';
      }
      return {id:message.id,role:message.role,content,createdAt:new Date(message.createdAt).getTime(),...(approval?{approval}: {}),...(toolApproval?{toolApproval}:{})};
    });
    chat.restoreConversation(conversation.agentId, conversation.sessionId, remoteMessages, conversation.workflowStatus);
    chat.setSession(conversation.sessionId,conversation.id);
    if (conversation.checkpointId) {
      chat.setCheckpoint(conversation.checkpointId, conversation.checkpointRevision ?? 0, conversation.workflowStatus);
      try {
        const checkpoint = await queryWorkflow(conversation.checkpointId);
        chat.setCheckpoint(checkpoint.checkpointId, checkpoint.revision, checkpoint.status);
      } catch {
        setNotice('历史画布已恢复，但未能刷新后端 Checkpoint 状态。');
      }
    }
    const xml=restoredXml||conversation.currentXml;
    diagram.restoreSnapshot(xml, restoredXml||conversation.lastAiXml, conversation.diagramTitle);
    history.setActive(conversation.id);
    draftCellsRef.current.clear();
    setPanel(conversation.agentId === '300000' ? 'diagram' : 'chat');
    setNotice('已从数据库恢复会话。');
  };

  const doLogout = () => {
    abortRef.current?.abort();
    modelSettings.clear();
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <main className="workspace-page">
      <header className="workspace-header">
        <div className="brand-lockup small"><span>AI</span><strong>Agent Workspace</strong></div>
        <div className="header-controls">
          {agentsQuery.data && <AgentSelector agents={agentsQuery.data} value={chat.agentId} disabled={!!chat.activeRequestId} onChange={(id) => void changeAgent(id)} />}
          <span className="session-chip" title={chat.sessionId || '尚未创建'}>{chat.sessionId ? `Session ${chat.sessionId.slice(0, 8)}` : '未创建会话'}</span>
          <button className="button" disabled={!chat.agentId || !!chat.activeRequestId} onClick={() => void requestNewSession()}>新建会话</button>
          <button className="button" onClick={() => setSettingsOpen(true)}>模型设置</button>
          <button className="button" onClick={() => window.open('/monitor', '_blank', 'noopener,noreferrer')}>运行监控 ↗</button>
          <button className="button" onClick={() => window.open('/eval', '_blank', 'noopener,noreferrer')}>Agent Eval ↗</button>
          <button className="button ghost" onClick={doLogout}>{user.username} · 退出</button>
        </div>
      </header>

      {(agentsQuery.error || notice || diagram.error) && (
        <div className={`notice-bar ${agentsQuery.error || diagram.error ? 'error' : ''}`}>
          {agentsQuery.error ? `Agent 列表加载失败：${agentsQuery.error.message}` : diagram.error || notice}
          {agentsQuery.error && <button onClick={() => void agentsQuery.refetch()}>重试</button>}
        </div>
      )}

      <nav className="mobile-tabs" aria-label="工作区视图">
        <button className={panel === 'history' ? 'active' : ''} onClick={() => setPanel('history')}>历史</button>
        <button className={panel === 'chat' ? 'active' : ''} onClick={() => setPanel('chat')}>AI 对话</button>
        {isDrawAgent && <button className={panel === 'diagram' ? 'active' : ''} onClick={() => setPanel('diagram')}>图表画布</button>}
      </nav>

      <div className={`workspace-grid ${historyCollapsed ? 'history-collapsed' : ''} ${isDrawAgent ? 'draw-mode' : 'chat-only'}`} style={{'--chat-width':`${chatWidth}px`} as React.CSSProperties}>
        <div className={panel !== 'history' ? 'mobile-hidden' : ''}>
          <HistorySidebar conversations={serverSnapshots} activeId={history.activeId} onNew={() => void requestNewSession()} onSelect={(conversation) => void restoreConversation(conversation)} onRemove={(id)=>void deleteConversation(id).then(()=>conversationsQuery.refetch())} collapsed={historyCollapsed} onToggle={() => setHistoryCollapsed((value) => { const next = !value; localStorage.setItem('history-sidebar-collapsed', String(next)); return next; })} />
        </div>
        <section className={`chat-panel ${panel !== 'chat' ? 'mobile-hidden' : ''}`}>
          <div className="panel-heading"><div><p className="eyebrow">AGENT</p><h1>会话</h1></div><AgentProgress phase={chat.phase} status={chat.statusText} nodes={chat.nodeCount} edges={chat.edgeCount} /></div>
          <MessageList messages={chat.messages} busy={!!chat.activeRequestId} onApprove={(approval) => send('', { checkpointId: approval.checkpointId, revision: approval.revision, decision: 'APPROVE' })} onRevise={(approval, revision) => send(revision, { checkpointId: approval.checkpointId, revision: approval.revision, decision: 'REVISE' })} onToolDecision={(approval,confirmed)=>{chat.setToolApprovalDecision(approval.callId,confirmed?'APPROVED':'DENIED');return send('',{checkpointId:approval.checkpointId,revision:approval.revision,decision:confirmed?'TOOL_APPROVE':'TOOL_DENY',toolCallId:approval.callId,confirmed,payload:{}});}} />
          <PromptComposer busy={!!chat.activeRequestId} disabled={!chat.agentId || agentsQuery.isError} canResume={chat.workflowStatus === 'PAUSED' && !!chat.checkpointId} onSend={send} onStop={stop} onResume={() => void send('', { checkpointId: chat.checkpointId, revision: chat.checkpointRevision, decision: 'CONTINUE' })} />
        </section>
        {isDrawAgent && <div className="panel-resizer" onPointerDown={(event)=>{const startX=event.clientX,start=chatWidth;let latest=start;const move=(e:PointerEvent)=>{latest=Math.max(360,Math.min(760,start+e.clientX-startX));setChatWidth(latest);};const up=()=>{window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',up);localStorage.setItem('chat-panel-width',String(latest));};window.addEventListener('pointermove',move);window.addEventListener('pointerup',up);}} />}
        {isDrawAgent && <section className={`diagram-panel ${panel !== 'diagram' ? 'mobile-hidden' : ''}`}>
          <DiagramToolbar editorRef={editorRef} />
          <DrawioEditor ref={editorRef} xml={diagram.currentXml} onChange={diagram.updateFromEditor} onExport={exportData} />
        </section>}
      </div>
      <ModelSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </main>
  );
}
