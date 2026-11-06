package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.json.JsonObjectSnapshot;
import java.util.Collections;
import java.util.Map;

/**
 * Web APIによる操作で受け渡すWebAPIイベントDtoを表す。
 *
 * <p>Version 1 explicitly projects payload fields; future internal fields are not automatically
 * public.
 *
 * @param id 識別子
 * @param sequence 発生順序
 * @param timestamp 記録日時
 * @param type 種別
 * @param version 形式のバージョン
 * @param sessionId 会話の識別子
 * @param turnId やり取りの識別子
 * @param runId 実行の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param correlationId 一連の処理を関連付ける識別子
 * @param parentEventId 親イベントの識別子
 * @param payload 通知内容
 */
public record WebApiEventDto(
    String id,
    long sequence,
    String timestamp,
    String type,
    int version,
    String sessionId,
    String turnId,
    String runId,
    String projectId,
    String correlationId,
    String parentEventId,
    Map<String, Object> payload) {
  /** 通知や要求の内容を、受け取った時点の状態で保持する。 */
  public WebApiEventDto {
    payload = payload == null ? null : Collections.unmodifiableMap(JsonObjectSnapshot.copy(payload));
  }

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static WebApiEventDto from(AgentEvent event, boolean cancelled, String apiKey) {
    return WebApiEventMapper.from(event, cancelled, apiKey);
  }
}
