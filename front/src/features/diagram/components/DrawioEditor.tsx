import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { DrawIoEmbed, type DrawIoEmbedRef } from 'react-drawio';
import { env } from '../../../shared/config/env';

export interface DrawioEditorHandle {
  exportDiagram: (format: 'svg' | 'png' | 'xmlsvg' | 'xmlpng') => void;
  fit: () => void;
}

interface Props {
  xml: string;
  onChange: (xml: string) => void;
  onExport: (data: string, format: string) => void;
}

export const DrawioEditor = forwardRef<DrawioEditorHandle, Props>(
  ({ xml, onChange, onExport }, forwardedRef) => {
    const editorRef = useRef<DrawIoEmbedRef>(null);
    const loadedXml = useRef('');

    useImperativeHandle(forwardedRef, () => ({
      exportDiagram: (format) => editorRef.current?.exportDiagram({ format }),
      fit: () => editorRef.current?.load({ xml: loadedXml.current || xml, autosave: true }),
    }));

    useEffect(() => {
      if (xml && xml !== loadedXml.current) {
        loadedXml.current = xml;
        editorRef.current?.load({ xml, autosave: true });
      }
    }, [xml]);

    return (
      <div className="drawio-shell" aria-label="Draw.io 编辑器">
        <DrawIoEmbed
          ref={editorRef}
          xml={xml}
          baseUrl={env.drawioBaseUrl}
          autosave
          urlParameters={{
            ui: 'kennedy',
            spin: true,
            libraries: true,
            saveAndExit: false,
            noSaveBtn: false,
          }}
          onLoad={() => { loadedXml.current = xml; }}
          onAutoSave={(event) => {
            if (event.xml) onChange(event.xml);
          }}
          onSave={(event) => {
            if (event.xml) onChange(event.xml);
          }}
          onExport={(event) => onExport(event.data, event.format)}
        />
      </div>
    );
  },
);

DrawioEditor.displayName = 'DrawioEditor';
