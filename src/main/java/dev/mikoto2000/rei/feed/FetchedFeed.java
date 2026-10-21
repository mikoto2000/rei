package dev.mikoto2000.rei.feed;

import java.util.List;

/**
 * フィードの購読と収集で受け渡す取得フィードを表す。
 *
 * @param title タイトル
 * @param siteUrl サイトURL
 * @param description 説明
 * @param items 項目一覧
 */
public record FetchedFeed(
    String title, String siteUrl, String description, List<FetchedFeedItem> items) {}
