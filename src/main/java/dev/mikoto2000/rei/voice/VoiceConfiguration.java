package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.application.session.ShellConversationService;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfiguration {
  @Bean AudioDeviceService audioDeviceService(VoiceProperties properties) {
    var devices=new AudioDeviceService(); devices.restoreSelection(properties.getDeviceId()); return devices;
  }
  @Bean VoiceEventPublisher voiceEventPublisher() { return new VoiceEventPublisher(); }
  @Bean(destroyMethod="close") HttpsVoiceAssetTransport voiceAssetTransport() {return new HttpsVoiceAssetTransport();}
  @Bean(destroyMethod="close") VoiceModelManager voiceModelManager(VoiceProperties properties,HttpsVoiceAssetTransport transport,VoiceEventPublisher events) {
    return new VoiceModelManager(Path.of(properties.getBundleDirectory()),VoiceModelManifest.pinned(),transport,status -> {
      var type=status.state()==VoiceModelManager.State.DOWNLOADING?VoiceEventPublisher.Type.MODEL_PROGRESS:VoiceEventPublisher.Type.MODEL_STATE;
      events.publish(type,status.state()+" "+status.bytes()+"/"+status.totalBytes()+" bytes "+status.asset()+" attempt="+status.attempt()+" "+status.failure());
    });
  }
  @Bean(destroyMethod="close") SherpaBackendFactory sherpaBackendFactory(VoiceModelManager models) {
    return new SherpaBackendFactory(()-> {try{return models.readyDirectory();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
  }
  @Bean(destroyMethod="close") VoiceInputCoordinator voiceInputCoordinator(
      SherpaBackendFactory backend,AudioDeviceService devices,VoiceEventPublisher events,
      ShellConversationService conversations,Clock clock) {
    return new VoiceInputCoordinator(new JavaSoundMicrophoneCapture(),backend,conversations::submit,events,clock);
  }
}