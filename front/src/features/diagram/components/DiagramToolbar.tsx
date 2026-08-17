import type { DrawioEditorHandle } from './DrawioEditor';
import { downloadText, safeFilename } from '../diagramXml';
import { useDiagramStore } from '../diagram.store';

interface Props {
  editorRef: React.RefObject<DrawioEditorHandle | null>;
}

export function DiagramToolbar({ editorRef }: Props) {
  const { currentXml, lastAiXml, title, setTitle, restoreAi, clear } = useDiagramStore();
  return (
    <div className="diagram-toolbar">
      <input className="diagram-title" aria-label="图表标题" value={title} onChange={(e) => setTitle(e.target.value)} />
      <div className="toolbar-actions">
        <button className="button tiny" onClick={() => editorRef.current?.fit()}>适应画布</button>
        <button className="button tiny" onClick={() => downloadText(currentXml, `${safeFilename(title)}.drawio`)}>导出 XML</button>
        <button className="button tiny" onClick={() => editorRef.current?.exportDiagram('svg')}>导出 SVG</button>
        <button className="button tiny" onClick={() => editorRef.current?.exportDiagram('png')}>导出 PNG</button>
        <button className="button tiny" disabled={!lastAiXml} onClick={restoreAi}>恢复 AI 版本</button>
        <button className="button tiny danger" onClick={clear}>清空</button>
      </div>
    </div>
  );
}
