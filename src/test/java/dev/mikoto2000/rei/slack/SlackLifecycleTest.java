package dev.mikoto2000.rei.slack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlackLifecycleTest {
  @Test void shellOrDisabledConfigurationNeverConnects() {
    var p=new SlackProperties();p.setEnabled(true);
    var factory=(java.util.function.Supplier<SlackSocketLifecycle.Connection>)()->{fail("Must not connect");return null;};
    try(var lifecycle=new SlackSocketLifecycle(p,false,Map.of("SLACK_BOT_TOKEN","dummy","SLACK_APP_TOKEN","dummy"),factory)){assertFalse(lifecycle.connectOnce());}
    p.setEnabled(false);
    try(var lifecycle=new SlackSocketLifecycle(p,true,Map.of("SLACK_BOT_TOKEN","dummy","SLACK_APP_TOKEN","dummy"),factory)){assertFalse(lifecycle.connectOnce());}
  }
  @Test void missingTokensOrFailedConnectionNeverAbortBackend() {
    var p=new SlackProperties();p.setEnabled(true);
    try(var lifecycle=new SlackSocketLifecycle(p,true,Map.of(),()->{fail("No tokens");return null;})){assertFalse(lifecycle.connectOnce());}
    try(var lifecycle=new SlackSocketLifecycle(p,true,Map.of("SLACK_BOT_TOKEN","dummy","SLACK_APP_TOKEN","dummy"),()->{throw new IllegalStateException("secret must not be logged");})){assertFalse(lifecycle.connectOnce());}
  }
  @Test void shutdownClosesConnectionOnce() {
    var p=new SlackProperties();p.setEnabled(true);var count=new java.util.concurrent.atomic.AtomicInteger();
    var lifecycle=new SlackSocketLifecycle(p,true,Map.of("SLACK_BOT_TOKEN","dummy","SLACK_APP_TOKEN","dummy"),()->new SlackSocketLifecycle.Connection(){public void pump(){}public void close(){count.incrementAndGet();}});
    assertTrue(lifecycle.connectOnce());assertTrue(lifecycle.connectOnce());lifecycle.close();lifecycle.close();assertEquals(1,count.get());
  }
  @Test void backendStartupFlagIsExplicit() {
    assertTrue(dev.mikoto2000.rei.StartupOptions.parse(new String[]{"--backend"},java.nio.file.Path.of(".")).backend());
    assertTrue(dev.mikoto2000.rei.StartupOptions.parse(new String[]{"--mode=server"},java.nio.file.Path.of(".")).backend());
    assertTrue(dev.mikoto2000.rei.StartupOptions.parse(new String[]{"--mode","server"},java.nio.file.Path.of(".")).backend());
    assertFalse(dev.mikoto2000.rei.StartupOptions.parse(new String[]{},java.nio.file.Path.of(".")).backend());
    assertFalse(dev.mikoto2000.rei.StartupOptions.parse(new String[]{"--mode=legacy-shell"},java.nio.file.Path.of(".")).backend());
  }
  @Test void stopDoesNotWaitForSlowInitializationAndClosesLateConnection() throws Exception {
    var p=new SlackProperties();p.setEnabled(true);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.atomic.AtomicInteger();
    var lifecycle=new SlackSocketLifecycle(p,true,Map.of("SLACK_BOT_TOKEN","dummy","SLACK_APP_TOKEN","dummy"),()->{
      entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
      return new SlackSocketLifecycle.Connection(){public void pump(){}public void close(){closed.incrementAndGet();}};
    });
    var connecting=java.util.concurrent.CompletableFuture.supplyAsync(lifecycle::connectOnce);
    assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
    try{assertTimeoutPreemptively(java.time.Duration.ofMillis(300),lifecycle::close);}finally{release.countDown();}
    connecting.get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,closed.get());
  }
}
