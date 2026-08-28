import { request } from '../../shared/api/httpClient';
import type { ArtifactRevisionRequest, ArtifactView } from './artifacts.types';

export const queryArtifacts = (conversationId: string) =>
  request<ArtifactView[]>(`/api/v1/artifacts?conversationId=${encodeURIComponent(conversationId)}`);

export const queryArtifact = (id: string) =>
  request<ArtifactView>(`/api/v1/artifacts/${encodeURIComponent(id)}`);

export const queryArtifactDiff = (targetId: string, baseId: string) =>
  request<string>(`/api/v1/artifacts/${encodeURIComponent(targetId)}/diff/${encodeURIComponent(baseId)}`);

export const rollbackArtifact = (id: string, body: ArtifactRevisionRequest) =>
  request<ArtifactView>(`/api/v1/artifacts/${encodeURIComponent(id)}/rollback`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const branchArtifact = (id: string, body: ArtifactRevisionRequest) =>
  request<ArtifactView>(`/api/v1/artifacts/${encodeURIComponent(id)}/branches`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
