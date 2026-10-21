package dev.mikoto2000.rei.ui.projection;

import dev.mikoto2000.rei.event.ErrorInformation;

/**
 * 実行状況の表示用整理で受け渡すエラー表示内容を表す。
 *
 * @param type 種別
 * @param message メッセージ
 * @param code コード
 */
public record ErrorView(String type, String message, String code) {
  /** 実行状況の表示用整理で利用する情報を、受け取った形式から変換する。 */
  static ErrorView from(ErrorInformation error) {
    return error == null ? null : new ErrorView(error.errorType(), error.message(), error.code());
  }
}
