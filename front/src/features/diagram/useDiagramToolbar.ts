import { useRef } from 'react';
import type { DrawioEditorHandle } from './components/DrawioEditor';
import { useDiagramStore } from './diagram.store';
import { safeFilename } from './diagramXml';

export function useDiagramToolbar() {
  const editorRef = useRef<DrawioEditorHandle>(null);
  const title = useDiagramStore((state) => state.title);

  const exportData = (data: string, format: string) => {
    const anchor = document.createElement('a');
    anchor.href = data;
    anchor.download = `${safeFilename(title)}.${format.includes('png') ? 'png' : 'svg'}`;
    anchor.click();
  };

  return { editorRef, exportData };
}
