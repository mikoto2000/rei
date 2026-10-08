package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

/** Tool output selection only; actual model usage remains owned by ModelCallBudget. */
final class FileResultBudget {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
  private static final TokenEstimator TOKENS=TokenEstimator.conservative();
  static boolean fits(Object value,int bytes,int tokens)throws IOException {
    byte[] encoded=JSON.writeValueAsBytes(value);return encoded.length<=bytes && TOKENS.text(new String(encoded,StandardCharsets.UTF_8))<=tokens;
  }
  static void require(Object value,int bytes,int tokens)throws IOException {
    if(!fits(value,bytes,tokens))throw new IOException("File result metadata exceeds byte/token budget; narrow the request or increase its budget");
  }
  static <T> T fit(T value,int bytes,int tokens,UnaryOperator<T> smaller)throws IOException {
    for(int attempt=0;attempt<128;attempt++){
      if(fits(value,bytes,tokens))return value;
      T next=smaller.apply(value);if(next.equals(value))break;value=next;
    }
    throw new IOException("File result metadata exceeds byte/token budget; narrow the request or increase its budget");
  }
  static String half(String value){int end=value.length()/2;if(end>0 && Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
}
