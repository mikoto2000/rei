package dev.mikoto2000.rei.artifact;
import java.time.Instant;
/** Public immutable delivery receipt. Storage references are opaque identities, never paths. */
public record Artifact(String artifactId,String owner,String projectId,String sessionId,String runId,String taskId,
    String mediaType,String filename,long size,String sha256,Instant createdAt,Instant expiresAt,
    String storageReference,String status) {}
