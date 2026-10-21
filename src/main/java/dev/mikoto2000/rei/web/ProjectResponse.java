package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すプロジェクト応答を表す。
 *
 * <p>Registered project identity and path, so clients can distinguish duplicate names.
 *
 * @param id 識別子
 * @param name 名前
 * @param path パス
 */
public record ProjectResponse(String id, String name, String path) {}
