package dev.mikoto2000.rei.voice;

/**
 * 音声による対話で受け渡す音声Deviceを表す。
 *
 * @param id 識別子
 * @param name 名前
 * @param description 説明
 * @param vendor 提供元
 * @param version 形式のバージョン
 */
public record AudioDevice(
    String id, String name, String description, String vendor, String version) {}
