package dev.mikoto2000.rei.llm;

/**
 * 用途に応じたモデル利用で受け渡す出力上限計画の見直しSubgoalを表す。
 *
 * @param id 識別子
 * @param goal 目標
 */
public record OutputLimitReplanSubgoal(String id, String goal) {}
