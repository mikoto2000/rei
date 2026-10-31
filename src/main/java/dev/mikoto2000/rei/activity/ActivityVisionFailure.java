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

  /** 本文や例外メッセージを含まない、最大16段の失敗診断。 */
  record Details(
      String errorType, java.util.List<String> causes, Integer httpStatus, boolean truncated) {
    Details {
      causes = java.util.List.copyOf(causes);
    }
  }

  /** SDK・SpringのHTTP例外を含む原因連鎖から、型とステータスだけを取り出す。 */
  static Details details(Throwable error) {
    var causes = new java.util.ArrayList<String>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    Integer status = null;
    var current = error;
    while (current != null && causes.size() < 16 && seen.add(current)) {
      causes.add(current.getClass().getName());
      if (status == null) {
        if (current instanceof com.openai.errors.OpenAIServiceException response)
          status = response.statusCode();
        else if (current
            instanceof
            org.springframework.web.reactive.function.client.WebClientResponseException response)
          status = response.getStatusCode().value();
        else if (current
            instanceof org.springframework.web.client.RestClientResponseException response)
          status = response.getStatusCode().value();
      }
      current = current.getCause();
    }
    return new Details(
        error == null ? "none" : error.getClass().getName(), causes, status, current != null);
  }

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
