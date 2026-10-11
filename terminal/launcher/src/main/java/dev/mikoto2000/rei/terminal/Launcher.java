package dev.mikoto2000.rei.terminal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.launcher.*;
import dev.mikoto2000.rei.cli.*;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;

public final class Launcher {
  public static void main(String[] args) {
    try{System.exit(execute(LauncherOptions.parse(args)));}
    catch(IllegalArgumentException invalid){System.err.println(invalid.getMessage());System.exit(2);}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();System.exit(130);}
    catch(IOException failure){System.err.println("Launcher failed: "+failure.getMessage());System.exit(1);}
  }
  static int execute(LauncherOptions options)throws IOException,InterruptedException {
    if(options.help()){System.out.println("rei --mode auto|client|server|legacy-shell [--project LOCAL_PATH] [--no-history] [--backend-jar JAR]\nrei server status\nrei server stop --yes\nlegacy-shell preserves the existing Shell; arguments after -- are passed to it.");return 0;}
    Path root=ReiDataDirectory.current();
    String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
    if(options.mode().equals("legacy-shell")) {
      var command=new ArrayList<String>(List.of(javaExecutable,"--enable-native-access=ALL-UNNAMED","-Djava.net.preferIPv4Stack=true","-Djava.awt.headless=false","-Drei.computer-use.diagnostics.enabled=true","-Drei.data-dir="+root,"-jar",options.backendJar().toString(),"--mode=legacy-shell"));
      if(options.project()!=null){command.add("--project");command.add(options.project().toString());}command.addAll(options.backendArguments());
      return new ProcessBuilder(command).inheritIO().start().waitFor();
    }
    String key=System.getenv("REI_API_KEY");
    if(key==null||key.isBlank())throw new IllegalArgumentException("REI_API_KEY is required. Configure the existing API key, or use --mode legacy-shell. No credentials were generated.");
    try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(new java.net.ProxySelector(){public List<java.net.Proxy> select(java.net.URI uri){return List.of(java.net.Proxy.NO_PROXY);}public void connectFailed(java.net.URI uri,java.net.SocketAddress address,IOException error){}}).build()) {
      var probe=new BackendConnectionProbe(http);var discovery=new BackendEndpointStore(root);
      boolean owned=BackendOwnership.isOwned(root);var endpoint=discovery.read();
      if(endpoint.isPresent()&&!discovery.readStorageId().filter(endpoint.get().storageId()::equals).isPresent())throw new IOException("Discovery storage identity mismatch");
      if("status".equals(options.action())) {
        System.out.println(endpoint.isPresent()?probe.check(endpoint.get(),key,owned).status():owned?"STARTING_OR_LEGACY_SHELL":"STOPPED");return 0;
      }
      if("stop".equals(options.action())) {
        if(!options.confirmed())throw new IllegalArgumentException("Stopping Backend affects every client. Use server stop --yes to confirm.");
        if(endpoint.isEmpty()||probe.check(endpoint.get(),key,owned).status()!=BackendConnectionProbe.Status.READY)throw new IOException("No verified Backend to stop; no process was signaled");
        try(var client=new BackendClient(endpoint.get().uri(),key)){client.post("/api/v1/instance/stop",Map.of("instanceId",endpoint.get().instanceId(),"storageId",endpoint.get().storageId()));}
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(BackendOwnership.isOwned(root)&&System.nanoTime()<deadline)Thread.sleep(100);
        if(BackendOwnership.isOwned(root))throw new IOException("Backend accepted stop but has not released storage ownership");
        System.out.println("Backend stopped");return 0;
      }
      if(endpoint.isPresent()&&owned) {
        var status=probe.check(endpoint.get(),key,true).status();
        if(status!=BackendConnectionProbe.Status.READY&&status!=BackendConnectionProbe.Status.STARTING&&status!=BackendConnectionProbe.Status.UNREACHABLE)throw new IOException("Backend discovery: "+status+"; refusing replacement");
      }
      if(!owned&&options.mode().equals("client"))throw new IOException("Backend is not running; client mode does not start it");
      if(!owned) {
        if(!Files.isRegularFile(options.backendJar()))throw new IOException("Backend JAR missing: "+options.backendJar());
        Path log=root.resolve("logs/backend.log");
        DetachedBackend.start(List.of(javaExecutable,"--enable-native-access=ALL-UNNAMED","-Djava.net.preferIPv4Stack=true","-Djava.awt.headless=false","-Drei.computer-use.diagnostics.enabled=true","-Drei.data-dir="+root,
            "-jar",options.backendJar().toString(),"--mode=server","--server.address=127.0.0.1","--server.port=0","--logging.file.name="+log,"--rei.conversation.concurrent-enabled=true"),Path.of("").toAbsolutePath(),log);
      }
      long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();BackendEndpoint ready=null;
      while(System.nanoTime()<deadline) {
        endpoint=discovery.read();owned=BackendOwnership.isOwned(root);
        if(endpoint.isPresent()&&!discovery.readStorageId().filter(endpoint.get().storageId()::equals).isPresent())throw new IOException("Discovery storage identity mismatch");
        if(endpoint.isPresent()&&owned) {
          var status=probe.check(endpoint.get(),key,true).status();if(status==BackendConnectionProbe.Status.READY){ready=endpoint.get();break;}
          if(status==BackendConnectionProbe.Status.AUTHENTICATION_FAILED||status==BackendConnectionProbe.Status.IDENTITY_MISMATCH||status==BackendConnectionProbe.Status.INCOMPATIBLE)throw new IOException("Backend discovery: "+status);
        }
        Thread.sleep(200);
      }
      if(ready==null)throw new IOException("Backend did not become ready. Inspect Backend logs; an existing legacy-shell may own storage.");
      if(options.mode().equals("server")){System.out.println("Backend READY: "+ready.instanceId()+" pid="+ready.pid());return 0;}
      Path clientData=Path.of(System.getProperty("user.home"),".rei-cli/history");
      try(var backend=new BackendClient(ready.uri(),key);var client=new TerminalClient(backend,clientData,options.noHistory())) {
        client.selectInitialProject(options.project());client.run();
      }
      return 0;
    }
  }
}
