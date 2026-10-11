package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.context.SmartLifecycle;
import org.slf4j.LoggerFactory;

/** Connection failures are contained to this opt-in channel; initialization never blocks startup. */
public final class SlackSocketLifecycle implements SmartLifecycle,AutoCloseable {
  public interface Connection extends AutoCloseable {void pump();}
  private final SlackProperties settings;private final boolean backend;
  private final Map<String,String> environment;private final Supplier<Connection> factory;
  private Connection connection;private ScheduledExecutorService worker;private volatile boolean running;
  private long retryAt;
  public SlackSocketLifecycle(SlackProperties settings,boolean backend,Map<String,String> environment,Supplier<Connection> factory) {
    this.settings=settings;this.backend=backend;this.environment=environment;this.factory=factory;
  }
  synchronized boolean connectOnce() {
    if(connection!=null)return true;
    if(!settings.isEnabled()||!backend)return false;
    if(environment.getOrDefault("SLACK_BOT_TOKEN","").isBlank()||environment.getOrDefault("SLACK_APP_TOKEN","").isBlank()) {
      LoggerFactory.getLogger(getClass()).warn("Slack disabled: SLACK_BOT_TOKEN and SLACK_APP_TOKEN are required");return false;
    }
    try{connection=factory.get();return connection!=null;}
    catch(RuntimeException error){LoggerFactory.getLogger(getClass()).warn("Slack connection unavailable; retry pending (credentials and error details omitted)");return false;}
  }
  @Override public synchronized void start() {
    if(running||!settings.isEnabled()||!backend)return;
    running=true;worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("rei-slack").factory());
    worker.scheduleWithFixedDelay(()-> {
      try {
        if(!running)return;
        if(connection==null&&System.currentTimeMillis()>=retryAt) {if(!connectOnce())retryAt=System.currentTimeMillis()+30_000;}
        var current=connection;if(current!=null)current.pump();
      }catch(RuntimeException unavailable){LoggerFactory.getLogger(getClass()).warn("Slack processing unavailable; durable receipts retained (error details omitted)");}
    },0,1,TimeUnit.SECONDS);
  }
  @Override public synchronized void stop() {
    running=false;if(worker!=null){worker.shutdownNow();worker=null;}
    if(connection!=null){try{connection.close();}catch(Exception ignored){LoggerFactory.getLogger(getClass()).warn("Slack shutdown incomplete (error details omitted)");}finally{connection=null;}}
  }
  @Override public boolean isRunning(){return running;}
  @Override public void close(){stop();}
}
