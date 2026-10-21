package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.Message;

/**
 * 会話の実行に必要な実行継続入力を扱う。
 *
 * <p>Narrow asynchronous dependency seam at the existing completed-tool-batch boundary.
 */
public interface RunContinuation {
  /**
   * 会話の実行で受け渡す待機中ツール一覧を表す。
   *
   * @param text テキスト
   * @param calls 呼び出し回数
   * @param completed 完了
   * @param argumentCorrections 引数補正一覧
   */
  record PendingTools(
      String text,
      List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls,
      List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> completed,
      Set<String> argumentCorrections) {
    /** 受け取った情報からPendingToolsを作成する。 */
    public PendingTools {
      calls = List.copyOf(calls);
      completed = List.copyOf(completed);
      argumentCorrections =
          argumentCorrections == null ? Set.of() : Set.copyOf(argumentCorrections);
    }

    /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public PendingTools(
        String text,
        List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls,
        List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> completed) {
      this(text, calls, completed, Set.of());
    }
  }

  /**
   * 会話の実行で受け渡す保存済みを表す。
   *
   * @param request 要求
   * @param messages メッセージ一覧
   * @param budget 予算
   * @param pendingTools 待機中ツール一覧
   */
  record Saved(
      String request,
      List<Message> messages,
      OutputLimitRunBudget.Snapshot budget,
      PendingTools pendingTools) {
    /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Saved(String request, List<Message> messages, OutputLimitRunBudget.Snapshot budget) {
      this(request, messages, budget, null);
    }
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  Saved restore(AgentRunContext owner);

  /** 会話の実行において、中断に該当するか判定する。 */
  boolean suspend(RunExecutionContext execution, List<Message> history);

  /** 会話の実行に関する関連付け親を処理する。 */
  default void attachParent(
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      java.util.function.Consumer<ChatExecutionResult> completed) {}

  /** 会話の実行に関する関連付けの解除親を処理する。 */
  default void detachParent(AgentRunContext owner) {}

  /** 会話の実行に関する終端を処理する。 */
  default void terminal(AgentRunContext owner) {}

  /** 要求を後続の処理へ引き渡すため、案内を受け付ける。 */
  default void acceptGuidance(AgentRunContext owner, List<Message> guidance) {}
}
