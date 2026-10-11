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
    assertFalse(dev.mikoto2000.rei.StartupOptions.parse(new String[]{},java.nio.file.Path.of(".")).backend());
  }
}
