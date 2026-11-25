package dev.mikoto2000.rei.modeloptions;

/** Controlled configuration guidance without endpoint values or credentials. */
public final class ResponsesConfigurationException extends IllegalStateException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  /** Build a safe diagnostic containing only supported configuration names and constraints. */
  public ResponsesConfigurationException() {
    super(
        "Responses の接続設定が対応条件と一致しません。rei.llm.features の対象用途（chat / output-limit-planner）について、"
            + "base-url（OpenAI）、model、api-key、fallback-to-default（false）、"
            + "endpoint-generation（1）、reasoning-replay（encrypted）、max-tools（36）、"
            + "supported-settings（reasoning-effort）、temperature（未指定）と"
            + " rei.llm.read-timeout（120秒以上）を確認し、変更後は再起動してください。");
  }
}
