export interface ArtifactView {
  id: string;
  conversationId: string;
  invocationId: string;
  parentArtifactId?: string;
  lineageId: string;
  branchName: string;
  artifactType: string;
  name: string;
  mimeType: string;
  content: string;
  contentHash: string;
  sizeBytes: number;
  versionNo: number;
  status: string;
  createdAt: string;
}

export interface ArtifactRevisionRequest {
  invocationId?: string;
  idempotencyKey: string;
  branchName?: string;
}
