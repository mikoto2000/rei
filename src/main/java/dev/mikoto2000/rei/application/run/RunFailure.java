package dev.mikoto2000.rei.application.run;

/**
 * 実行の受付と状態管理で受け渡す実行失敗情報を表す。
 *
 * @param type 種別
 * @param message メッセージ
 */
public record RunFailure(String type, String message) {}
