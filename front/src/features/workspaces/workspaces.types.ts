export type WorkspaceRole = 'OWNER' | 'EDITOR' | 'VIEWER';

export interface WorkspaceItem {
  id: string;
  name: string;
  description?: string;
  role: WorkspaceRole;
  createdAt: string;
  updatedAt?: string;
}

export interface WorkspaceMember {
  username: string;
  displayName?: string;
  role: WorkspaceRole;
  createdAt: string;
}

export interface CreateWorkspaceRequest {
  name: string;
  description?: string;
}

export interface MemberRequest {
  username: string;
  role: WorkspaceRole;
}
