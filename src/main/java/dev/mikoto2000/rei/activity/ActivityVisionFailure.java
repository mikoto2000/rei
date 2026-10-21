package dev.mikoto2000.rei.activity;

/** 作業状況の把握における作業状況画像認識失敗情報の選択肢。 */
enum ActivityVisionFailure {
  /** 出力上限。 */
  OUTPUT_LIMIT,
  /** タイムアウト。 */
  TIMEOUT,
  /** 検証。 */
  VALIDATION,
  /** その他。 */
  OTHER;

  /** 得られた情報を分類し、後続の判断で扱い分けられるようにする。 */
  static ActivityVisionFailure classify(Throwable error) {
    for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
      if (error instanceof ActivityOutputParser.InvalidOutput invalid)
        return invalid.errors().stream().anyMatch(e -> "output_limit".equals(e.code()))
            ? OUTPUT_LIMIT
            : VALIDATION;
      if (error instanceof java.net.SocketTimeoutException
          || error instanceof java.util.concurrent.TimeoutException
          || error instanceof java.net.http.HttpTimeoutException) return TIMEOUT;
    }
    return OTHER;
  }
}
