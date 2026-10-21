package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す要約テーマ機能の構成を表す。
 *
 * <p>One immutable alias/group snapshot, so reload cannot mix two configuration generations.
 *
 * @param projects プロジェクト一覧
 * @param groups グループ一覧
 */
public record SummaryThemeConfiguration(
    ProjectNameNormalizer projects, SummaryThemeGroups groups) {}
