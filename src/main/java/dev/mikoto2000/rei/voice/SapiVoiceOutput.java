package dev.mikoto2000.rei.voice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Windows-installed SAPI voice; no speech service, SSML, or audio file output. */
public final class SapiVoiceOutput implements VoiceSpeechOutput {
  @FunctionalInterface interface Launcher { Process start(List<String> command) throws IOException; }
  private final Launcher launcher;
  private final java.time.Duration timeout;
  private final AtomicReference<Process> active=new AtomicReference<>();
  private static final String SCRIPT="""
      $ErrorActionPreference='Stop'
      [Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
      $request=[Console]::In.ReadToEnd() | ConvertFrom-Json
      $speaker=New-Object -ComObject SAPI.SpVoice
      try {
        $found=$false
        foreach($token in $speaker.GetVoices()) {
          if($token.GetDescription() -ceq [string]$request.voice) {
            $speaker.Voice=$token; $found=$true; break
          }
        }
        if(-not $found) { throw 'Selected installed voice unavailable' }
        [void]$speaker.Speak([string]$request.text,16)
      } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($speaker) }
      """;
  public SapiVoiceOutput(){this(args->{
    if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))
      throw new IOException("Windows SAPI required");
    return new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
  });}
  SapiVoiceOutput(Launcher launcher){this(launcher,java.time.Duration.ofMinutes(3));}
  SapiVoiceOutput(Launcher launcher,java.time.Duration timeout){
    this.launcher=Objects.requireNonNull(launcher);this.timeout=Objects.requireNonNull(timeout);
    if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(java.time.Duration.ofMinutes(3))>0)
      throw new IllegalArgumentException("Invalid local speech timeout");
  }
  @Override public void speak(String voice,String text,BooleanSupplier current) throws Exception {
    if(voice==null||voice.isBlank()||voice.length()>256||text==null||text.length()>16384)
      throw new IllegalArgumentException("Invalid local speech request");
    if(!current.getAsBoolean())return;
    String windows=System.getenv().getOrDefault("SystemRoot","C:/Windows");
    var command=List.of(Path.of(windows,"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
        "-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",
        Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE)));
    var process=launcher.start(command);
    if(!active.compareAndSet(null,process)){terminate(process);throw new IllegalStateException("Local playback already active");}
    var watchdog=new Thread(()->{
      try{TimeUnit.NANOSECONDS.sleep(timeout.toNanos());if(active.get()==process)process.destroyForcibly();}
      catch(InterruptedException cancelled){ /* Normal completion or explicit stop. */ }
    },"voice-playback-deadline");watchdog.setDaemon(true);
    try {
      watchdog.start();
      try(var stdin=process.getOutputStream()) {
        stdin.write(new ObjectMapper().writeValueAsBytes(Map.of("voice",voice,"text",text)));
      }
      long deadline=System.nanoTime()+timeout.toNanos();
      while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
        if(!current.getAsBoolean())return;
        if(System.nanoTime()-deadline>=0)throw new IOException("Local playback timed out");
      }
      if(!current.getAsBoolean())return;
      if(process.exitValue()!=0)throw new IOException("Local playback failed");
    } finally {watchdog.interrupt();terminate(process);active.compareAndSet(process,null);}
  }
  private static void terminate(Process process) {
    if(!process.isAlive())return;
    process.destroyForcibly();
    try {if(!process.waitFor(3000,TimeUnit.MILLISECONDS))throw new IllegalStateException("Local playback did not terminate");}
    catch(InterruptedException e){Thread.currentThread().interrupt();}
  }
  @Override public void stop(){var process=active.get();if(process!=null)process.destroyForcibly();}
}
