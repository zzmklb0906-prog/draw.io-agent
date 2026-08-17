import { describe, expect, it } from 'vitest';
import { isValidDrawioXml } from './diagramXml';

describe('Draw.io XML validation', () => {
  it('accepts valid mxGraphModel and rejects malformed or unrelated XML', () => {
    expect(isValidDrawioXml('<mxGraphModel><root/></mxGraphModel>')).toBe(true);
    expect(isValidDrawioXml('<mxGraphModel><root></mxGraphModel>')).toBe(false);
    expect(isValidDrawioXml('<html/>')).toBe(false);
  });
});
