package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** No shell wrapper, token argument or normal Codex home. Native tools disabled and OS reads restricted. */
public final class WslPlanAppServerLauncher implements PlanAppServerClient.Launcher {
  private final PlanAppServerProperties settings;
  public WslPlanAppServerLauncher(PlanAppServerProperties settings){this.settings=settings;}
  public String authority(){return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256((settings.distribution()+"|"+settings.executable()+"|"+configuration()).getBytes(StandardCharsets.UTF_8));}
  public PlanAppServerClient.Started start(TokenSet tokens,Duration timeout,BooleanSupplier stop) {
    if(!settings.enabled()||!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))throw ChatGptException.of(ROUTE_UNAVAILABLE);
    Path home=null;
    try {
      Files.createDirectories(settings.runtimeDirectory());
      if(Files.isSymbolicLink(settings.runtimeDirectory()))throw ChatGptException.of(PERMISSION_DENIED);
      home=Files.createTempDirectory(settings.runtimeDirectory(),"rei-plan-");Path cwd=Files.createDirectory(home.resolve("workspace"));
      Files.writeString(home.resolve("config.toml"),configuration().replace("[permissions.rei-read.network]","\""+settings.executable()+"\" = \"read\"\n[permissions.rei-read.network]"));
      String linuxCwd=linuxPath(cwd);
      var version=process(home,linuxCwd,null,List.of("--version")).start();
      try {
        if(!version.waitFor(Math.min(10000,Math.max(1,timeout.toMillis())),TimeUnit.MILLISECONDS)){version.destroyForcibly();throw ChatGptException.of(TIMEOUT);}
        byte[] output=version.getInputStream().readNBytes(256);
        if(version.exitValue()!=0||!new String(output,StandardCharsets.UTF_8).strip().equals("codex-cli 0.154.0"))throw ChatGptException.of(PROTOCOL);
      } finally {version.destroyForcibly();}
      if(stop.getAsBoolean())throw ChatGptException.of(CANCELLED);
      var child=process(home,linuxCwd,tokens.accessToken(),List.of("app-server","--listen","stdio://")).start();
      return new PlanAppServerClient.Started(new ProcessSession(child,home),linuxCwd);
    } catch(ChatGptException e){remove(home);throw e;}catch(Exception e){remove(home);throw ChatGptException.of(ROUTE_UNAVAILABLE);}
  }
  ProcessBuilder process(Path home,String linuxCwd,String token,List<String> arguments) {
    String systemRoot=System.getenv("SystemRoot");if(systemRoot==null)throw ChatGptException.of(ROUTE_UNAVAILABLE);
    var command=new ArrayList<String>(List.of(Path.of(systemRoot,"System32","wsl.exe").toString(),"--distribution",settings.distribution(),"--cd",linuxCwd,"--exec"));
    if(!arguments.isEmpty()&&arguments.getFirst().equals("app-server"))command.addAll(List.of("/bin/sh","-c","printf '%s\\n' \"$$\" > \"$CODEX_HOME/process.pid\"; exec \"$@\"","rei-plan-launcher","/usr/bin/timeout","--signal=TERM","--kill-after=1s","125s"));
    command.add(settings.executable());command.addAll(arguments);
    var builder=new ProcessBuilder(command).directory(home.toFile());var env=builder.environment();env.clear();
    for(String name:List.of("SystemRoot","TEMP","TMP")){String value=System.getenv(name);if(value!=null)env.put(name,value);}
    env.put("PATH",systemRoot+"\\System32");env.put("CODEX_HOME",home.toString());env.put("HOME",home.toString());
    env.put("WSLENV","CODEX_HOME/p:HOME/p"+(token==null?"":":REI_PLAN_ACCESS_TOKEN/u"));
    if(token!=null)env.put("REI_PLAN_ACCESS_TOKEN",token);
    return builder;
  }
  private void stopLinux(Path home,String signal) {
    try {
      String pid=Files.readString(home.resolve("process.pid")).strip();
      if(!pid.matches("[1-9][0-9]{0,9}")||Long.parseLong(pid)<=1)return;
      // Check the dedicated home in the process environment before signalling its private timeout group.
      String script="test -r /proc/\"$1\"/environ || exit 0; tr '\\000' '\\n' < /proc/\"$1\"/environ | grep -F -x -- \"CODEX_HOME=$2\" >/dev/null || exit 0; kill -\"$3\" -- -\"$1\" 2>/dev/null || kill -\"$3\" \"$1\" 2>/dev/null";
      var base=process(home,linuxPath(home),null,List.of("--version"));
      var command=List.of(base.command().getFirst(),"--distribution",settings.distribution(),"--exec","/bin/sh","-c",script,"rei-plan-stop",pid,linuxPath(home),signal);
      var kill=new ProcessBuilder(command);kill.environment().clear();kill.environment().putAll(base.environment());
      kill.redirectOutput(ProcessBuilder.Redirect.DISCARD);kill.redirectError(ProcessBuilder.Redirect.DISCARD);
      var child=kill.start();if(!child.waitFor(1500,TimeUnit.MILLISECONDS))child.destroyForcibly();
    } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignored){}
  }
  static String linuxPath(Path path) {
    String value=path.toAbsolutePath().normalize().toString().replace('\\','/');
    if(!value.matches("[A-Za-z]:/.*")||value.contains("\n")||value.contains("\r"))throw ChatGptException.of(PERMISSION_DENIED);
    return "/mnt/"+Character.toLowerCase(value.charAt(0))+value.substring(2);
  }
  static String configuration(){return """
      model_provider = "rei_plan"
      default_permissions = "rei-read"
      web_search = "disabled"
      [model_providers.rei_plan]
      name = "Rei own ChatGPT Plan"
      base_url = "https://api.openai.com/v1"
      env_key = "REI_PLAN_ACCESS_TOKEN"
      wire_api = "responses"
      requires_openai_auth = false
      supports_websockets = false
      request_max_retries = 0
      stream_max_retries = 0
      [features]
      shell_tool = false
      multi_agent = false
      unified_exec = false
      apps = false
      browser_use = false
      computer_use = false
      view_image = false
      goals = false
      guardian_approval = false
      worktrees = false
      unbounded_connection_retries = false
      [permissions.rei-read.filesystem]
      ":root" = "deny"
      ":minimal" = "read"
      ":workspace_roots" = "read"
      [permissions.rei-read.network]
      enabled = false
      [shell_environment_policy]
      inherit = "none"
      """;}
  private static void remove(Path root) {
    if(root==null)return;
    // Only the newly created dedicated home; never follow links or recurse into external roots.
    try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())if(p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))Files.deleteIfExists(p);}catch(IOException ignored){}
  }
  private final class ProcessSession implements PlanAppServerRpc.Session {
    private static final Object EOF=new Object();private final BlockingQueue<Object> lines=new ArrayBlockingQueue<>(64);
    private final Process process;private final Path home;private final BufferedWriter writer;
    ProcessSession(Process process,Path home) {
      this.process=process;this.home=home;writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
      Thread.ofVirtual().start(()->{
        try(var in=new BufferedInputStream(process.getInputStream())) {
          var line=new ByteArrayOutputStream();int total=0,value;
          while((value=in.read())!=-1){if(++total>2*1024*1024||line.size()>=65536)throw new IOException();if(value=='\n'){if(!lines.offer(line.toString(StandardCharsets.UTF_8),1,TimeUnit.SECONDS))throw new IOException();line.reset();}else line.write(value);}
          if(line.size()>0)lines.offer(line.toString(StandardCharsets.UTF_8));
        } catch(Exception ignored){process.destroyForcibly();}finally{while(!lines.offer(EOF)){lines.poll();}}
      });
      Thread.ofVirtual().start(()->{try(var in=process.getErrorStream()){byte[] bytes=new byte[4096];int total=0,n;while((n=in.read(bytes))!=-1){total+=n;if(total>1048576){process.destroyForcibly();break;}}}catch(IOException ignored){}});
    }
    public void send(String json){try{writer.write(json);writer.newLine();writer.flush();}catch(IOException e){throw ChatGptException.of(TRANSPORT);}}
    public String poll(Duration timeout){try{Object line=lines.poll(timeout.toNanos(),TimeUnit.NANOSECONDS);return line==null?"":line==EOF?null:(String)line;}catch(InterruptedException e){Thread.currentThread().interrupt();throw ChatGptException.of(CANCELLED);}}
    public void close(){boolean interrupted=Thread.interrupted();try{writer.close();if(!process.waitFor(500,TimeUnit.MILLISECONDS)){stopLinux(home,"TERM");if(!process.waitFor(300,TimeUnit.MILLISECONDS)){stopLinux(home,"KILL");process.descendants().forEach(p->p.destroyForcibly());process.destroyForcibly();process.waitFor(500,TimeUnit.MILLISECONDS);}}}catch(InterruptedException e){interrupted=true;stopLinux(home,"KILL");process.destroyForcibly();}catch(Exception ignored){stopLinux(home,"KILL");process.destroyForcibly();}finally{remove(home);if(interrupted)Thread.currentThread().interrupt();}}
  }
}
