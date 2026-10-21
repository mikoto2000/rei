package dev.mikoto2000.rei.activity;

import java.util.List;

/**
 * 作業状況の把握で受け渡す分類診断情報を表す。
 *
 * @param matchedRuleIds 一致した規則識別子一覧
 * @param winningRuleId winning規則識別子
 * @param source 取得元
 * @param confidence 確信度
 * @param classificationUsable 分類利用可能
 * @param visionRequired 画像認識必須
 * @param visionRequiredReason 画像認識必須理由
 * @param unknownReasons 不明理由一覧
 * @param entertainmentDisposition 娯楽扱い
 * @param entertainmentConfidence 娯楽確信度
 * @param matchedEntertainmentRuleId 一致した娯楽規則識別子
 * @param entertainmentSource 娯楽取得元
 * @param entertainmentReason 娯楽理由
 */
public record ClassificationDiagnostics(
    List<String> matchedRuleIds,
    String winningRuleId,
    String source,
    ActivityFieldConfidence confidence,
    boolean classificationUsable,
    boolean visionRequired,
    String visionRequiredReason,
    List<String> unknownReasons,
    EntertainmentDisposition entertainmentDisposition,
    double entertainmentConfidence,
    String matchedEntertainmentRuleId,
    String entertainmentSource,
    String entertainmentReason) {}
