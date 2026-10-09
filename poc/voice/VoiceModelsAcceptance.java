package dev.mikoto2000.rei.voice;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.AudioSystem;
/** Explicit integration: fresh approved download or network-forbidden reuse; optional brief mic startup. */
public final class VoiceModelsAcceptance {
  public static void main(String[] args) throws Exception {
    if(args.length<4||args.length>5)throw new IllegalArgumentException("ROOT download|offline APPROVED_ID JAPANESE_WAV [EXACT_MIC_NAME]");
    Path root=Path.of(args[0]);boolean download=args[1].equals("download");
    if(!download&&!args[1].equals("offline"))throw new IllegalArgumentException("Unknown mode");
    var manifest=VoiceModelManifest.pinned();
    System.out.println("MANIFEST "+manifest.id()+" / "+manifest.totalBytes()+" bytes");
    for(var asset:manifest.assets())System.out.println(asset.path()+" / "+asset.license()+" / "+asset.url());
    try(var https=new HttpsVoiceAssetTransport();var manager=new VoiceModelManager(root,manifest,
      download?https:(a,p,c,progress)->{throw new AssertionError("Offline reuse attempted a download");},s->System.out.println("MODEL "+s))) {
      if(download) {
        if(Files.exists(root.resolve("managed")))throw new IllegalArgumentException("Fresh download acceptance requires an empty managed directory");
        if(!manager.install(args[2]))throw new IllegalStateException("Fresh download did not start");
        long deadline=System.nanoTime()+VoiceModelManager.DOWNLOAD_TIMEOUT.plusMinutes(1).toNanos();
        while(manager.busy()&&System.nanoTime()<deadline)Thread.sleep(100);
        if(manager.status().state()!=VoiceModelManager.State.READY)throw new IllegalStateException("Download failed: "+manager.status());
      }
      Path bundle=manager.readyDirectory();manifest.verify(bundle);
      float[] samples;
      try(var source=AudioSystem.getAudioInputStream(Path.of(args[3]).toFile());var pcm=AudioFormatConverter.toPcm16(source)) {
        samples=VoicePcm.decode(pcm.readAllBytes());
      }
      try(var factory=new SherpaBackendFactory(()->bundle)) {
        for(int cycle=0;cycle<3;cycle++)try(var backend=factory.open(VoiceSettings.defaults())) {
          var assembler=new SpeechSegmentAssembler(VoiceSettings.defaults(),Clock.systemUTC());var results=new ArrayList<String>();
          var padded=Arrays.copyOf(samples,samples.length+32000);
          for(int offset=0;offset+512<=padded.length;offset+=512) {
            var frame=Arrays.copyOfRange(padded,offset,offset+512);
            var decision=assembler.accept(frame,backend.vad().probability(frame));
            if(decision.segment()!=null)results.add(backend.recognizer().recognize(decision.segment()));
          }
          String text=String.join("",results);
          if(!text.contains("こんにちは")||!text.contains("音声入力")||!text.contains("日本語"))throw new IllegalStateException("Japanese fixture recognition failed");
          System.out.println("NATIVE CYCLE "+cycle+": "+text);
        }
        if(args.length==5) {
          var devices=new AudioDeviceService();var matches=devices.devices().stream().filter(d->d.name().equals(args[4])).toList();
          if(matches.size()!=1)throw new IllegalStateException("Explicit microphone missing or ambiguous");
          var events=new VoiceEventPublisher();events.subscribe(e->{if(e.type()==VoiceEventPublisher.Type.STATE_CHANGED)System.out.println("MIC "+e.detail());});
          var project=new dev.mikoto2000.rei.core.project.ProjectContext(UUID.randomUUID().toString(),"model-startup-acceptance",root);
          var target=new dev.mikoto2000.rei.application.input.ConversationTarget(project,"model-startup");
          try(var voice=new VoiceInputCoordinator(new JavaSoundMicrophoneCapture(),factory,
              input->{throw new AssertionError("Diagnostic must never submit to Agent");},events,Clock.systemUTC())) {
            voice.startDiagnostic(target,matches.getFirst(),VoiceSettings.defaults());
            if(voice.awaitStartup(VoiceRuntimeLimits.COMMAND_STARTUP)!=VoiceInputCoordinator.State.LISTENING)throw new IllegalStateException("Newly acquired models did not start microphone input");
            Thread.sleep(1000);voice.off();long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(voice.state()==VoiceInputCoordinator.State.STOPPING&&System.nanoTime()<stop)Thread.sleep(20);
            if(voice.state()!=VoiceInputCoordinator.State.OFF)throw new IllegalStateException("Microphone did not stop cleanly");
          }
        }
      }
      System.out.println("MODEL ACCEPTANCE PASSED: "+args[1]+" / verified whole bundle / Japanese JNI cycles=3"+(args.length==5?" / explicit microphone LISTENING -> OFF":""));
    }
  }
}