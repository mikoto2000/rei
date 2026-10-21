package dev.mikoto2000.rei.artifact;

import java.time.Instant;

/**
 * 成果物の管理で受け渡す成果物を表す。
 *
 * <p>Public immutable delivery receipt. Storage references are opaque identities, never paths.
 *
 * @param artifactId 成果物識別子
 * @param owner 所有者
 * @param projectId 所属プロジェクトの識別子
 * @param sessionId 会話の識別子
 * @param runId 実行の識別子
 * @param taskId タスク識別子
 * @param mediaType メディア種別
 * @param filename ファイル名
 * @param size 大きさ
 * @param sha256 内容の同一性を照合するSHA-256ハッシュ
 * @param createdAt 作成日時
 * @param expiresAt 有効期限
 * @param storageReference 保存参照先
 * @param status 状態
 */
public record Artifact(
    String artifactId,
    String owner,
    String projectId,
    String sessionId,
    String runId,
    String taskId,
    String mediaType,
    String filename,
    long size,
    String sha256,
    Instant createdAt,
    Instant expiresAt,
    String storageReference,
    String status) {}
