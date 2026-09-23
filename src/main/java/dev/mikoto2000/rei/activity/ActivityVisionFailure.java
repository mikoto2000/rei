package dev.mikoto2000.rei.activity;

enum ActivityVisionFailure {
  OUTPUT_LIMIT,TIMEOUT,VALIDATION,OTHER;
  static ActivityVisionFailure classify(Throwable error) {
    for(int i=0;error!=null && i<8;i++,error=error.getCause()) {
      if(error instanceof ActivityOutputParser.InvalidOutput invalid)
        return invalid.errors().stream().anyMatch(e->"output_limit".equals(e.code()))?OUTPUT_LIMIT:VALIDATION;
      if(error instanceof java.net.SocketTimeoutException || error instanceof java.util.concurrent.TimeoutException || error instanceof java.net.http.HttpTimeoutException)return TIMEOUT;
    }
    return OTHER;
  }
}
