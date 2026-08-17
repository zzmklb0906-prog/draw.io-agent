import { create } from 'zustand';
import { EMPTY_DIAGRAM, isValidDrawioXml } from './diagramXml';

interface DiagramStore {
  currentXml: string;
  lastAiXml: string;
  title: string;
  error: string;
  applyAiXml: (xml: string) => boolean;
  applyDraftXml: (xml: string) => boolean;
  restoreSnapshot: (currentXml: string, lastAiXml: string, title: string) => void;
  updateFromEditor: (xml: string) => void;
  restoreAi: () => void;
  clear: () => void;
  setTitle: (title: string) => void;
}

export const useDiagramStore = create<DiagramStore>((set, get) => ({
  currentXml: EMPTY_DIAGRAM,
  lastAiXml: '',
  title: '未命名图表',
  error: '',
  applyAiXml: (xml) => {
    if (!isValidDrawioXml(xml)) {
      set({ error: 'Agent 返回的 Draw.io XML 无效，已保留上一版本。' });
      return false;
    }
    set({ currentXml: xml, lastAiXml: xml, error: '' });
    return true;
  },
  applyDraftXml: (xml) => {
    if (!isValidDrawioXml(xml)) return false;
    set({ currentXml: xml, error: '' });
    return true;
  },
  restoreSnapshot: (currentXml, lastAiXml, title) => {
    set({
      currentXml: isValidDrawioXml(currentXml) ? currentXml : EMPTY_DIAGRAM,
      lastAiXml: isValidDrawioXml(lastAiXml) ? lastAiXml : '',
      title: title || '未命名图表',
      error: '',
    });
  },
  updateFromEditor: (currentXml) => {
    if (isValidDrawioXml(currentXml)) set({ currentXml, error: '' });
  },
  restoreAi: () => {
    const { lastAiXml } = get();
    if (lastAiXml) set({ currentXml: lastAiXml, error: '' });
  },
  clear: () => set({ currentXml: EMPTY_DIAGRAM, lastAiXml: '', error: '' }),
  setTitle: (title) => set({ title }),
}));
