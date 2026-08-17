import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { ConversationDraft, ConversationSnapshot } from './history.types';

const MAX_HISTORY = 30;

function inferTitle(draft: ConversationDraft) {
  const firstPrompt = draft.messages.find((message) => message.role === 'user')?.content.trim();
  return draft.title || firstPrompt?.slice(0, 28) || '新的绘图会话';
}

interface HistoryState {
  conversations: ConversationSnapshot[];
  activeId: string;
  save: (draft: ConversationDraft, id?: string) => string;
  remove: (id: string) => void;
  setActive: (id: string) => void;
}

export const useHistoryStore = create<HistoryState>()(
  persist(
    (set) => ({
      conversations: [],
      activeId: '',
      save: (draft, requestedId) => {
        const id = requestedId || crypto.randomUUID();
        const now = Date.now();
        set((state) => {
          const previous = state.conversations.find((item) => item.id === id);
          const snapshot: ConversationSnapshot = {
            ...draft,
            id,
            title: inferTitle(draft),
            createdAt: previous?.createdAt ?? now,
            updatedAt: now,
          };
          return {
            activeId: id,
            conversations: [snapshot, ...state.conversations.filter((item) => item.id !== id)]
              .sort((a, b) => b.updatedAt - a.updatedAt)
              .slice(0, MAX_HISTORY),
          };
        });
        return id;
      },
      remove: (id) => set((state) => ({
        activeId: state.activeId === id ? '' : state.activeId,
        conversations: state.conversations.filter((item) => item.id !== id),
      })),
      setActive: (activeId) => set({ activeId }),
    }),
    { name: 'drawio-agent-conversation-history', version: 2, migrate: (persisted) => { const state = persisted as HistoryState; return { ...state, conversations: (state.conversations ?? []).map((item) => ({ ...item, workflowStatus: item.workflowStatus ?? 'IDLE' })) }; } },
  ),
);
