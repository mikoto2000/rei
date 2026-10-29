package dev.mikoto2000.rei.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 言語モデルの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@ConfigurationProperties(prefix = "rei.llm")
public class LlmProperties {

  /** 既定最大出力トークン数。 */
  private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

  /** 最大出力トークン数。 */
  private Integer maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;

  /** チャット通信でデータが届くまでの待ち時間。 */
  private java.time.Duration readTimeout = ChatStreamTimeoutInterceptor.DEFAULT_READ_TIMEOUT;

  public java.time.Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(java.time.Duration readTimeout) {
    ChatStreamTimeoutInterceptor.validatedReadTimeoutMillis(readTimeout);
    this.readTimeout = readTimeout;
  }

  /** 出力上限。 */
  private OutputLimit outputLimit = new OutputLimit();

  /** 機能と対応するサーバーの対応。 */
  private Map<String, Server> features = new LinkedHashMap<>();

  /** 用途に応じたモデル利用で参照する最大出力トークン数を返す。 */
  public Integer getMaxOutputTokens() {
    if (maxOutputTokens == null || maxOutputTokens <= 0) {
      return DEFAULT_MAX_OUTPUT_TOKENS;
    }
    return maxOutputTokens;
  }

  /** 用途に応じたモデル利用で使用する最大出力トークン数を設定する。 */
  public void setMaxOutputTokens(Integer maxOutputTokens) {
    this.maxOutputTokens = maxOutputTokens;
  }

  /** 用途に応じたモデル利用で参照する出力上限を返す。 */
  public OutputLimit getOutputLimit() {
    return outputLimit;
  }

  /** 用途に応じたモデル利用で使用する出力上限を設定する。 */
  public void setOutputLimit(OutputLimit outputLimit) {
    this.outputLimit = outputLimit == null ? new OutputLimit() : outputLimit;
  }

  /** 用途に応じたモデル利用で参照するFeaturesを返す。 */
  public Map<String, Server> getFeatures() {
    return features;
  }

  /** 用途に応じたモデル利用で使用するFeaturesを設定する。 */
  public void setFeatures(Map<String, Server> features) {
    this.features = features == null ? new LinkedHashMap<>() : features;
  }

  /** 用途に応じたモデル利用で用いる機能を返す。 */
  public Server feature(String name) {
    return features.get(name);
  }

  /** 用途に応じたモデル利用に必要なサーバーを扱う。 */
  public static class Server {
    /** 最大出力トークン数。 */
    private Integer maxOutputTokens;

    /** 用途に応じたモデル利用で参照する最大出力トークン数を返す。 */
    public Integer getMaxOutputTokens() {
      return maxOutputTokens;
    }

    /** 用途に応じたモデル利用で使用する最大出力トークン数を設定する。 */
    public void setMaxOutputTokens(Integer maxOutputTokens) {
      if (maxOutputTokens != null && maxOutputTokens <= 0)
        throw new IllegalArgumentException("max-output-tokens must be positive");
      this.maxOutputTokens = maxOutputTokens;
    }

    /** 接続先の基準URL。 */
    private String baseUrl;

    /** APIキー。 */
    private String apiKey;

    /** モデル。 */
    private String model;

    /** 生成の揺らぎ。 */
    private Double temperature;

    /** 用途に応じたモデル利用で参照する接続先の基準URLを返す。 */
    public String getBaseUrl() {
      return baseUrl;
    }

    /** 用途に応じたモデル利用で使用する接続先の基準URLを設定する。 */
    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    /** 用途に応じたモデル利用で参照するAPIキーを返す。 */
    public String getApiKey() {
      return apiKey;
    }

    /** 用途に応じたモデル利用で使用するAPIキーを設定する。 */
    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    /** 用途に応じたモデル利用で参照するモデルを返す。 */
    public String getModel() {
      return model;
    }

    /** 用途に応じたモデル利用で使用するモデルを設定する。 */
    public void setModel(String model) {
      this.model = model;
    }

    /** 用途に応じたモデル利用で参照する生成の揺らぎを返す。 */
    public Double getTemperature() {
      return temperature;
    }

    /** 用途に応じたモデル利用で使用する生成の揺らぎを設定する。 */
    public void setTemperature(Double temperature) {
      this.temperature = temperature;
    }

    /** 用途に応じたモデル利用において、利用者指定サーバーの有無を判定する。 */
    public boolean hasCustomServer() {
      return baseUrl != null && !baseUrl.isBlank();
    }
  }

  /** 用途に応じたモデル利用に必要な出力上限を扱う。 */
  public static class OutputLimit {
    /** 既定最大計画の見直し回数ごとの目標。 */
    private static final int DEFAULT_MAX_REPLANS_PER_GOAL = 2;

    /** 既定最大小さな目標ごとの計画の見直し。 */
    private static final int DEFAULT_MAX_SUBGOALS_PER_REPLAN = 8;

    /** 既定最大言語モデル呼び出し回数ごとの実行。 */
    private static final int DEFAULT_MAX_LLM_CALLS_PER_RUN = 120;

    /** 最大計画の見直し回数ごとの目標。 */
    private Integer maxReplansPerGoal = DEFAULT_MAX_REPLANS_PER_GOAL;

    /** 最大小さな目標ごとの計画の見直し。 */
    private Integer maxSubgoalsPerReplan = DEFAULT_MAX_SUBGOALS_PER_REPLAN;

    /** 最大言語モデル呼び出し回数ごとの実行。 */
    private Integer maxLlmCallsPerRun = DEFAULT_MAX_LLM_CALLS_PER_RUN;

    /** 言語モデル呼び出し予算有効状態。 */
    private boolean llmCallBudgetEnabled = true;

    /** 用途に応じたモデル利用において、言語モデル呼び出し予算有効状態かどうかを判定する。 */
    public boolean isLlmCallBudgetEnabled() {
      return llmCallBudgetEnabled;
    }

    /** 用途に応じたモデル利用で使用する言語モデル呼び出し予算有効状態を設定する。 */
    public void setLlmCallBudgetEnabled(boolean enabled) {
      llmCallBudgetEnabled = enabled;
    }

    /** 最大ツール引数補正一覧ごとの実行。 */
    private Integer maxToolArgumentCorrectionsPerRun =
        OutputLimitRunBudget.DEFAULT_MAX_ARGUMENT_CORRECTIONS;

    /** 最大使用トークン数の合計ごとの実行。 */
    private long maxTotalTokensPerRun;

    /** 最大使用トークン数の合計ごとの目標。 */
    private long maxTotalTokensPerGoal;

    /** 用途に応じたモデル利用で参照する最大使用トークン数の合計ごとの目標を返す。 */
    public long getMaxTotalTokensPerGoal() {
      return maxTotalTokensPerGoal;
    }

    /** 用途に応じたモデル利用で使用する最大使用トークン数の合計ごとの目標を設定する。 */
    public void setMaxTotalTokensPerGoal(long value) {
      if (value < 0)
        throw new IllegalArgumentException("max-total-tokens-per-goal must be nonnegative");
      maxTotalTokensPerGoal = value;
    }

    /** 用途に応じたモデル利用で参照する最大使用トークン数の合計ごとの実行を返す。 */
    public long getMaxTotalTokensPerRun() {
      return maxTotalTokensPerRun;
    }

    /** 用途に応じたモデル利用で使用する最大使用トークン数の合計ごとの実行を設定する。 */
    public void setMaxTotalTokensPerRun(long value) {
      if (value < 0)
        throw new IllegalArgumentException("max-total-tokens-per-run must be nonnegative");
      maxTotalTokensPerRun = value;
    }

    /** 用途に応じたモデル利用で参照する最大計画の見直し回数ごとの目標を返す。 */
    public Integer getMaxReplansPerGoal() {
      return positiveOrDefault(maxReplansPerGoal, DEFAULT_MAX_REPLANS_PER_GOAL);
    }

    /** 用途に応じたモデル利用で使用する最大計画の見直し回数ごとの目標を設定する。 */
    public void setMaxReplansPerGoal(Integer maxReplansPerGoal) {
      this.maxReplansPerGoal = maxReplansPerGoal;
    }

    /** 用途に応じたモデル利用で参照する最大小さな目標ごとの計画の見直しを返す。 */
    public Integer getMaxSubgoalsPerReplan() {
      return positiveOrDefault(maxSubgoalsPerReplan, DEFAULT_MAX_SUBGOALS_PER_REPLAN);
    }

    /** 用途に応じたモデル利用で使用する最大小さな目標ごとの計画の見直しを設定する。 */
    public void setMaxSubgoalsPerReplan(Integer maxSubgoalsPerReplan) {
      this.maxSubgoalsPerReplan = maxSubgoalsPerReplan;
    }

    /** 用途に応じたモデル利用で参照する最大言語モデル呼び出し回数ごとの実行を返す。 */
    public Integer getMaxLlmCallsPerRun() {
      return positiveOrDefault(maxLlmCallsPerRun, DEFAULT_MAX_LLM_CALLS_PER_RUN);
    }

    /** 用途に応じたモデル利用で使用する最大言語モデル呼び出し回数ごとの実行を設定する。 */
    public void setMaxLlmCallsPerRun(Integer maxLlmCallsPerRun) {
      this.maxLlmCallsPerRun = maxLlmCallsPerRun;
    }

    /** 用途に応じたモデル利用で参照する最大ツール引数補正一覧ごとの実行を返す。 */
    public Integer getMaxToolArgumentCorrectionsPerRun() {
      return maxToolArgumentCorrectionsPerRun == null
          ? OutputLimitRunBudget.DEFAULT_MAX_ARGUMENT_CORRECTIONS
          : maxToolArgumentCorrectionsPerRun;
    }

    /** 用途に応じたモデル利用で使用する最大ツール引数補正一覧ごとの実行を設定する。 */
    public void setMaxToolArgumentCorrectionsPerRun(Integer value) {
      if (value != null && value < 0)
        throw new IllegalArgumentException(
            "max-tool-argument-corrections-per-run must be nonnegative");
      maxToolArgumentCorrectionsPerRun = value;
    }

    /** 用途に応じたモデル利用で用いる正のまたは既定を返す。 */
    private int positiveOrDefault(Integer value, int defaultValue) {
      return value == null || value <= 0 ? defaultValue : value;
    }
  }
}
