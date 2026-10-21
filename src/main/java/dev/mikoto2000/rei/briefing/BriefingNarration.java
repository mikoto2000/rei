package dev.mikoto2000.rei.briefing;

import java.util.List;

/**
 * 日次情報の案内で受け渡す日次案内Narrationを表す。
 *
 * @param overview 概要
 * @param cautionPoints 確認時に注意する点
 * @param nextActions 次操作一覧
 */
public record BriefingNarration(
    String overview, List<String> cautionPoints, List<String> nextActions) {}
