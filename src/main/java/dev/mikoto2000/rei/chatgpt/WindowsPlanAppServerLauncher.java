package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicit MXC, no WSL/legacy fallback, selected own OAuth only in the server environment. */
public final class WindowsPlanAppServerLauncher implements PlanAppServerClient.Launcher {
  private static final String DISABLED_PLUGINS = """
      plugins = false
      remote_plugin = false
      plugin_sharing = false
      recommended_plugins = false
      skip_host_skill_discovery = true
      skill_mcp_dependency_install = false
      skill_search = false
      """;
  private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"rei-native-app-server-deadline");thread.setDaemon(true);return thread;});
  static {DEADLINES.setRemoveOnCancelPolicy(true);}
  private static final List<String> PLATFORM=List.of("SystemRoot","SystemDrive","WINDIR","ProgramFiles","ProgramFiles(x86)","ProgramW6432","ProgramData","ALLUSERSPROFILE");
  private final PlanAppServerProperties settings;
  public WindowsPlanAppServerLauncher(PlanAppServerProperties settings){this.settings=settings;}
  public String version(){return "0.162.1";}
  public String platform(){return "windows";}
  public String authority(){return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(("windows|"+settings.executable()+"|"+configuration()).getBytes(StandardCharsets.UTF_8));}
  public PlanAppServerClient.Started start(TokenSet tokens,Duration timeout,BooleanSupplier stop) {
    if(!settings.enabled()||!settings.executionMode().equals("windows")||!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))throw ChatGptException.of(ROUTE_UNAVAILABLE);
    var deadline=new PlanProcessDeadline(timeout);
    Path home=null;
    try {
      if(!Files.isRegularFile(Path.of(settings.executable())))throw ChatGptException.of(ROUTE_UNAVAILABLE);
      Files.createDirectories(settings.runtimeDirectory());
      if(Files.isSymbolicLink(settings.runtimeDirectory()))throw ChatGptException.of(PERMISSION_DENIED);
      home=Files.createTempDirectory(settings.runtimeDirectory(),"rei-plan-windows-");
      Path cwd=Files.createDirectory(home.resolve("workspace"));
      Files.writeString(home.resolve("config.toml"),configuration());
      if(stop.getAsBoolean())throw ChatGptException.of(CANCELLED);
      deadline.remaining();
      var probe=process(home,cwd,null,List.of("--version")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      try {
        if(!probe.waitFor(deadline.probeWaitMillis(),TimeUnit.MILLISECONDS)){probe.destroyForcibly();throw ChatGptException.of(TIMEOUT);}
        String output=new String(probe.getInputStream().readNBytes(256),StandardCharsets.UTF_8).strip();
        if(probe.exitValue()!=0||!output.equals("codex-cli "+version()))throw ChatGptException.of(PROTOCOL);
      } finally {probe.destroyForcibly();}
      if(stop.getAsBoolean())throw ChatGptException.of(CANCELLED);
      deadline.remaining();
      var child=process(home,cwd,tokens.accessToken(),List.of("app-server","--listen","stdio://")).start();
      long remaining;
      try {remaining=deadline.remaining().toNanos();}
      catch(ChatGptException expired){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();throw expired;}
      var expiry=DEADLINES.schedule(()->{if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}},remaining,TimeUnit.NANOSECONDS);
      child.onExit().thenRun(()->expiry.cancel(false));
      Path ownedHome=home;
      return new PlanAppServerClient.Started(new AppServerProcessSession(child,
          ()->{child.descendants().forEach(ProcessHandle::destroy);child.destroy();},
          ()->{child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();},
          ()->remove(ownedHome)),cwd.toString());
    } catch(ChatGptException e){remove(home);throw e;}catch(Exception e){remove(home);throw ChatGptException.of(ROUTE_UNAVAILABLE);}
  }
  ProcessBuilder process(Path home,Path cwd,String token,List<String> args) {
    var command=new ArrayList<String>();command.add(settings.executable());command.addAll(args);
    var process=new ProcessBuilder(command).directory(cwd.toFile());var env=process.environment();env.clear();
    for(String key:PLATFORM){String value=System.getenv(key);if(value!=null)env.put(key,value);}
    String systemRoot=env.get("SystemRoot"),systemDrive=env.get("SystemDrive");
    if(systemRoot==null||systemDrive==null||!systemDrive.matches("[A-Za-z]:"))throw ChatGptException.of(ROUTE_UNAVAILABLE);
    env.put("PATH",Path.of(systemRoot,"System32").toString());env.put("ComSpec",Path.of(systemRoot,"System32","cmd.exe").toString());
    for(String key:List.of("HOME","USERPROFILE","LOCALAPPDATA","APPDATA","TEMP","TMP","CODEX_HOME"))env.put(key,home.toString());
    if(token!=null)env.put("REI_PLAN_ACCESS_TOKEN",token);
    return process;
  }
  String configuration() {
    String publicKeys="\"SystemRoot\",\"SystemDrive\",\"WINDIR\",\"ProgramFiles\",\"ProgramFiles(x86)\",\"ProgramW6432\",\"ProgramData\",\"ALLUSERSPROFILE\",\"PATH\",\"ComSpec\",\"TEMP\",\"TMP\",\"USERPROFILE\",\"LOCALAPPDATA\",\"APPDATA\"";
    return WslPlanAppServerLauncher.configuration()
        .replace("[permissions.rei-read.filesystem]",DISABLED_PLUGINS+"[permissions.rei-read.filesystem]")
        .replace("[permissions.rei-read.network]",quote(settings.executable().replace('\\','/'))+" = \"read\"\n[permissions.rei-read.network]")
        .replace("inherit = \"none\"","inherit = \"all\"\ninclude_only = ["+publicKeys+"]")+"\n[windows]\nsandbox = \"mxc\"\n";
  }
  private static String quote(String value){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid runtime path");}}
  private static void remove(Path root) {
    if(root==null)return;
    // MXC releases directory handles asynchronously; close the walk before deleting its root.
    for(int attempt=0;attempt<20&&Files.exists(root);attempt++) {
      try {
        List<Path> owned;
        try(var paths=Files.walk(root)){owned=paths.sorted(Comparator.reverseOrder()).toList();}
        for(Path p:owned)if(p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))Files.deleteIfExists(p);
        return;
      } catch(java.io.IOException ignored){try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
    }
  }
}
