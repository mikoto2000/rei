export interface DeliveryArtifact {
  artifactId: string;
  owner: string;
  projectId: string;
  sessionId: string | null;
  runId: string | null;
  taskId: string | null;
  mediaType: string;
  filename: string;
  size: number;
  sha256: string;
  createdAt: string;
  expiresAt: string | null;
  storageReference: string;
  status: string;
}
export interface ArtifactPage {
  items: DeliveryArtifact[];
  nextCursor: string | null;
}
export interface ArtifactPreview {
  artifact: DeliveryArtifact;
  text: string | null;
  dataUrl: string | null;
}
export interface ArtifactSaveReceipt {
  path: string;
  size: number;
  sha256: string;
}
export interface ArtifactSelection {
  projectId: string;
  sessionId: string | null;
  runId: string | null;
  artifactId?: string;
}
