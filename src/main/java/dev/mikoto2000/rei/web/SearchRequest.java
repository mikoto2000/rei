package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡す検索要求を表す。
 *
 * @param query 検索条件
 * @param vectorTopK ベクトル上位件数
 * @param webTopK Web上位件数
 * @param threshold 判定の境界値
 */
public record SearchRequest(String query, Integer vectorTopK, Integer webTopK, Double threshold) {}
