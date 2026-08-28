import { request } from '../../shared/api/httpClient';
import type { CreateWorkspaceRequest, MemberRequest, WorkspaceItem, WorkspaceMember } from './workspaces.types';

export const queryWorkspaces = () =>
  request<WorkspaceItem[]>('/api/v1/workspaces');

export const createWorkspace = (body: CreateWorkspaceRequest) =>
  request<WorkspaceItem>('/api/v1/workspaces', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const queryWorkspaceMembers = (workspaceId: string) =>
  request<WorkspaceMember[]>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members`);

export const putWorkspaceMember = (workspaceId: string, body: MemberRequest) =>
  request<boolean>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const deleteWorkspaceMember = (workspaceId: string, username: string) =>
  request<boolean>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(username)}`, {
    method: 'DELETE',
  });
