package dev.mikoto2000.rei.subagent;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Narrow transient read failures only. Returned error strings and model/provider failures are not replayed. */
final class SubAgentReadToolRetries {
  private final AtomicInteger remaining,attempts=new AtomicInteger();private final List<String> failures=new ArrayList<>();
  SubAgentReadToolRetries(int max){if(max<0||max>3)throw new IllegalArgumentException("Read retries must be 0 to 3");remaining=new AtomicInteger(max);}
  int attempts(){return attempts.get();}
  synchronized List<String> history(){return List.copyOf(failures);}
  private synchronized void failure(){if(failures.size()<4)failures.add("TRANSIENT_READ_TOOL_FAILURE");}
  String call(Supplier<String> invoke,Runnable authorize,Runnable check,BooleanSupplier eligible) {
    boolean retrying=false;
    while(true) {
      check.run();authorize.run();
      if(retrying){if(!eligible.getAsBoolean())throw new IllegalStateException("Read Tool retry is no longer permitted");attempts.incrementAndGet();}
      try{return invoke.get();}
      catch(RuntimeException error) {
        if(!transientFailure(error)||!eligible.getAsBoolean())throw error;
        failure();check.run();if(remaining.getAndUpdate(value->Math.max(0,value-1))<=0)throw error;
        try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Read retry cancelled");}
        retrying=true;
      }
    }
  }
  private static boolean transientFailure(RuntimeException error) {
    if(error instanceof org.springframework.dao.TransientDataAccessException)return true;
    if(!(error instanceof java.io.UncheckedIOException||error instanceof org.springframework.web.client.ResourceAccessException))return false;
    Throwable cause=error.getCause();for(int depth=0;cause!=null&&depth<4;depth++,cause=cause.getCause()) {
      if(cause instanceof java.net.ConnectException||cause instanceof java.net.SocketTimeoutException||cause instanceof java.net.http.HttpTimeoutException)return true;
    }
    return false;
  }
}
