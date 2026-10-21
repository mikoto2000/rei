package dev.mikoto2000.rei.externalagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * 外部エージェントへの委譲で受け渡す外部エージェント要求を表す。
 *
 * @param agent エージェント
 * @param action 操作
 * @param task タスク
 * @param projectRoot 作業対象のルートディレクトリ
 * @param target 対象
 * @param context 文脈
 * @param runId 実行の識別子
 * @param delegationId 委譲識別子
 * @param externalSessionId 外部会話の識別子
 */
public record ExternalAgentRequest(
    Agent agent,
    Action action,
    String task,
    Path projectRoot,
    Path target,
    String context,
    String runId,
    String delegationId,
    String externalSessionId) {
  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentRequest(
      Agent agent,
      Action action,
      String task,
      Path projectRoot,
      Path target,
      String context,
      String runId,
      String delegationId) {
    this(agent, action, task, projectRoot, target, context, runId, delegationId, null);
  }

  /** 外部エージェントへの委譲におけるエージェントの選択肢。 */
  public enum Agent {
    /** Codexの設定または実行の窓口。 */
    CODEX,
    /** Claudeの設定または実行の窓口。 */
    CLAUDE
  }

  /** 外部エージェントへの委譲における操作の選択肢。 */
  public enum Action {
    /** レビュー。 */
    REVIEW,
    /** 教材レビュー。 */
    MATERIAL_REVIEW,
    /** 提案修正。 */
    PROPOSE_FIX,
    /** 実装を依頼する操作。 */
    IMPLEMENT
  }

  /** 指定や設定に応じた対象を利用するため、対象を確定する。 */
  public static Path resolveTarget(Path projectRoot, String target) {
    try {
      Path root = projectRoot.toRealPath();
      if (!Files.isDirectory(root))
        throw new IllegalArgumentException("Current project is not a directory");
      if (target == null || target.isBlank()) return null;
      Path input = Path.of(target);
      Path requested = input.isAbsolute() ? input : root.resolve(input);
      // Check the actual location, including project paths reached through links/junctions.
      Path canonical = requested.toRealPath();
      if (!canonical.startsWith(root))
        throw new IllegalArgumentException("Target must be inside the current project");
      return canonical;
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException(
          "Target path is invalid; use an absolute path or a path relative to the current project");
    } catch (NoSuchFileException error) {
      throw new IllegalArgumentException("Current project or target does not exist");
    } catch (IOException error) {
      throw new IllegalArgumentException("Current project or target is unavailable");
    }
  }
}
