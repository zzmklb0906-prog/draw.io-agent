export const EMPTY_DIAGRAM =
  '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';

export function isValidDrawioXml(xml: string): boolean {
  const value = xml.trim();
  if (!value || (!value.includes('<mxGraphModel') && !value.includes('<mxfile'))) return false;
  const parsed = new DOMParser().parseFromString(value, 'application/xml');
  return parsed.querySelector('parsererror') === null;
}

export function buildDraftDiagram(cells: Iterable<string>): string {
  return `<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>${[...cells].join('')}</root></mxGraphModel>`;
}

export function downloadText(content: string, filename: string, type = 'application/xml') {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function safeFilename(title: string) {
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12);
  const cleaned = title.replace(/[\\/:*?"<>|]/g, '-').trim().slice(0, 50);
  return `${cleaned || 'diagram'}-${stamp}`;
}
