package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 補助エージェントの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@ConfigurationProperties(prefix = "rei.subagents")
public class SubAgentProperties {
  /** ディレクトリ。 */
  private Path directory = ReiDataDirectory.current().resolve("config/subagents");

  /** モデル一覧。 */
  private Set<String> models = Set.of();

  /** 独立した最大言語モデル呼び出し回数。 */
  private int standaloneMaxLlmCalls;

  /** 独立した最大使用トークン数の合計。 */
  private long standaloneMaxTotalTokens;

  /** 最大一時的なモデル再試行回数。 */
  private int maxTransientModelRetries;

  /** 最大一時的な読み取りツール再試行回数。 */
  private int maxTransientReadToolRetries;

  /** 永続化された有効状態。 */
  private boolean durableEnabled;

  /** 依存関係有効状態。 */
  private boolean dagEnabled;

  /** 評価の合意有効状態。 */
  private boolean consensusEnabled;

  /** 評価の合意評価の判断有効状態。 */
  private boolean consensusJudgeEnabled;

  /** 補助エージェントへの委譲において、評価の合意有効状態かどうかを判定する。 */
  public boolean isConsensusEnabled() {
    return consensusEnabled;
  }

  /** 補助エージェントへの委譲で使用する評価の合意有効状態を設定する。 */
  public void setConsensusEnabled(boolean value) {
    consensusEnabled = value;
  }

  /** 補助エージェントへの委譲において、評価の合意評価の判断有効状態かどうかを判定する。 */
  public boolean isConsensusJudgeEnabled() {
    return consensusJudgeEnabled;
  }

  /** 補助エージェントへの委譲で使用する評価の合意評価の判断有効状態を設定する。 */
  public void setConsensusJudgeEnabled(boolean value) {
    consensusJudgeEnabled = value;
  }

  /** 依存関係タイムアウト。 */
  private java.time.Duration dagTimeout = java.time.Duration.ofSeconds(120);

  /** 補助エージェントへの委譲において、依存関係有効状態かどうかを判定する。 */
  public boolean isDagEnabled() {
    return dagEnabled;
  }

  /** 補助エージェントへの委譲で使用する依存関係有効状態を設定する。 */
  public void setDagEnabled(boolean value) {
    dagEnabled = value;
  }

  /** 補助エージェントへの委譲で参照する依存関係タイムアウトを返す。 */
  public java.time.Duration getDagTimeout() {
    return dagTimeout;
  }

  /** 補助エージェントへの委譲で使用する依存関係タイムアウトを設定する。 */
  public void setDagTimeout(java.time.Duration value) {
    if (value == null
        || value.compareTo(java.time.Duration.ofSeconds(1)) < 0
        || value.compareTo(java.time.Duration.ofSeconds(120)) > 0)
      throw new IllegalArgumentException("DAG timeout must be 1 to 120 seconds");
    dagTimeout = value;
  }

  /** 永続化された最大使用トークン数の合計。 */
  private long durableMaxTotalTokens;

  /** 補助エージェントへの委譲において、永続化された有効状態かどうかを判定する。 */
  public boolean isDurableEnabled() {
    return durableEnabled;
  }

  /** 補助エージェントへの委譲で使用する永続化された有効状態を設定する。 */
  public void setDurableEnabled(boolean value) {
    durableEnabled = value;
  }

  /** 補助エージェントへの委譲で参照する永続化された最大使用トークン数の合計を返す。 */
  public long getDurableMaxTotalTokens() {
    return durableMaxTotalTokens;
  }

  /** 補助エージェントへの委譲で使用する永続化された最大使用トークン数の合計を設定する。 */
  public void setDurableMaxTotalTokens(long value) {
    if (value < 0)
      throw new IllegalArgumentException("Durable child token limit must be nonnegative");
    durableMaxTotalTokens = value;
  }

  /** 補助エージェントへの委譲で参照する最大一時的な読み取りツール再試行回数を返す。 */
  public int getMaxTransientReadToolRetries() {
    return maxTransientReadToolRetries;
  }

  /** 補助エージェントへの委譲で使用する最大一時的な読み取りツール再試行回数を設定する。 */
  public void setMaxTransientReadToolRetries(int value) {
    if (value < 0 || value > 3)
      throw new IllegalArgumentException("Transient read Tool retries must be 0 to 3");
    maxTransientReadToolRetries = value;
  }

  /** 補助エージェントへの委譲で参照する最大一時的なモデル再試行回数を返す。 */
  public int getMaxTransientModelRetries() {
    return maxTransientModelRetries;
  }

  /** 補助エージェントへの委譲で使用する最大一時的なモデル再試行回数を設定する。 */
  public void setMaxTransientModelRetries(int value) {
    if (value < 0 || value > 3)
      throw new IllegalArgumentException("Transient model retries must be 0 to 3");
    maxTransientModelRetries = value;
  }

  /** 補助エージェントへの委譲で参照する独立した最大言語モデル呼び出し回数を返す。 */
  public int getStandaloneMaxLlmCalls() {
    return standaloneMaxLlmCalls;
  }

  /** 補助エージェントへの委譲で使用する独立した最大言語モデル呼び出し回数を設定する。 */
  public void setStandaloneMaxLlmCalls(int value) {
    if (value < 0 || value > 1000)
      throw new IllegalArgumentException("Standalone SubAgent call limit must be 0 to 1000");
    standaloneMaxLlmCalls = value;
  }

  /** 補助エージェントへの委譲で参照する独立した最大使用トークン数の合計を返す。 */
  public long getStandaloneMaxTotalTokens() {
    return standaloneMaxTotalTokens;
  }

  /** 補助エージェントへの委譲で使用する独立した最大使用トークン数の合計を設定する。 */
  public void setStandaloneMaxTotalTokens(long value) {
    if (value < 0)
      throw new IllegalArgumentException("Standalone SubAgent token limit must be nonnegative");
    standaloneMaxTotalTokens = value;
  }

  /** 補助エージェントへの委譲で参照するディレクトリを返す。 */
  public Path getDirectory() {
    return directory;
  }

  /** 補助エージェントへの委譲で使用するディレクトリを設定する。 */
  public void setDirectory(Path directory) {
    this.directory = directory;
  }

  /** 補助エージェントへの委譲で参照するモデル一覧を返す。 */
  public Set<String> getModels() {
    return models;
  }

  /** 補助エージェントへの委譲で使用するモデル一覧を設定する。 */
  public void setModels(Set<String> models) {
    this.models = Set.copyOf(models);
  }
}
