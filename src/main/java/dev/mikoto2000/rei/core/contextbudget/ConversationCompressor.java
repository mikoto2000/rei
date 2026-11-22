package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/** モデルへ渡す文脈の管理に必要な会話Compressorを扱う。 */
@FunctionalInterface
public interface ConversationCompressor {
  /**
   * モデルへ渡す文脈の管理で受け渡す要約を表す。
   *
   * <p>Summary text usage, excluding separately reported reasoning tokens; null preserves
   * conservative validation for legacy compressors.
   *
   * @param text テキスト
   * @param outputTokens 出力トークン数
   */
  record Summary(String text, Integer outputTokens) {
    /** Use reported visible-text usage, falling back to the conservative estimate when unknown. */
    public long textTokens(TokenEstimator estimator) {
      return outputTokens != null && outputTokens > 0 ? outputTokens : estimator.text(text);
    }
  }

  /** 要約本文とモデルの思考を含め、要求全体に予約する生成トークン数。 */
  default int generationTokens(int summaryTokens) {
    return summaryTokens;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  String summarize(
      String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest);

  /** 対象の内容を、要点が分かる形にまとめる。 */
  default String summarize(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget != null)
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    return summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest);
  }

  /** 利用者が要点を把握できるよう、結果を要約する。 */
  default Summary summarizeResult(
      String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest) {
    return new Summary(
        summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest), null);
  }

  /** 利用者が要点を把握できるよう、結果を要約する。 */
  default Summary summarizeResult(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget,
      java.time.Duration timeout) {
    return budget == null
        ? summarizeResult(previousSummary, newlyCompressible, maxTokens, ownerRequest)
        : summarizeResult(previousSummary, newlyCompressible, maxTokens, ownerRequest, budget);
  }

  /** A tighter writing target must not lower the hard limit of a repair request. */
  default Summary summarizeResult(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget,
      java.time.Duration timeout,
      int targetTokens) {
    return summarizeResult(
        previousSummary, newlyCompressible, maxTokens, ownerRequest, budget, timeout);
  }

  default Summary summarizeResult(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    return new Summary(
        summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest, budget), null);
  }
}
