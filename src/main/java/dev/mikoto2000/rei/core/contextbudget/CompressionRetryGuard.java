package dev.mikoto2000.rei.core.contextbudget;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Bounded local failure state. It contains settings and limits, never conversation text. */
final class CompressionRetryGuard {
  record Decision(boolean allowed,boolean announce,long waitSeconds,String lastReason) {}
  private static final int CAPACITY=256;
  private static final class Failure {
    String configuration,reason;
    int failures;
    long retryAt,inputLimit=Long.MAX_VALUE;
    boolean announced;
  }
  private final LinkedHashMap<String,Failure> failures=new LinkedHashMap<>(16,.75f,true);
  private final LongSupplier milliseconds;
  CompressionRetryGuard(){this(()->System.nanoTime()/1_000_000);}
  CompressionRetryGuard(LongSupplier milliseconds){this.milliseconds=milliseconds;}
  private Failure state(String key,String configuration) {
    var failure=failures.get(key);
    if(failure!=null&&!failure.configuration.equals(configuration)){failures.remove(key);return null;}
    return failure;
  }
  synchronized long inputLimit(String key,String configuration,long standard) {
    var failure=state(key,configuration);return failure==null?standard:Math.min(standard,failure.inputLimit);
  }
  synchronized Decision decision(String key,String configuration) {
    var failure=state(key,configuration);
    if(failure==null||milliseconds.getAsLong()>=failure.retryAt)return new Decision(true,false,0,null);
    boolean announce=!failure.announced;failure.announced=true;
    return new Decision(false,announce,Math.max(1,(failure.retryAt-milliseconds.getAsLong()+999)/1000),failure.reason);
  }
  synchronized long failed(String key,String configuration,String reason,long before,int summaryTokens,
      int cooldownSeconds,boolean shrink) {
    var failure=state(key,configuration);
    if(failure==null){failure=new Failure();failure.configuration=configuration;failures.put(key,failure);}
    failure.failures=Math.min(4,failure.failures+1);failure.reason=reason;failure.announced=false;
    long wait=(long)cooldownSeconds*(1L<<(failure.failures-1));failure.retryAt=milliseconds.getAsLong()+wait*1000;
    if(shrink)failure.inputLimit=Math.min(failure.inputLimit,Math.max(2L*summaryTokens,before/2));
    while(failures.size()>CAPACITY)failures.remove(failures.keySet().iterator().next());
    return wait;
  }
  synchronized void succeeded(String key){failures.remove(key);}
}
