package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.application.session.ShellConversationService;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfiguration {
  @Bean AudioDeviceService audioDeviceService(VoiceProperties properties,VoiceEventPublisher events) {
    var devices=new AudioDeviceService();devices.restoreSelection(properties.getDeviceId());
    events.subscribe(event->{
      if(event.type()==VoiceEventPublisher.Type.DEVICE_CHANGED||event.type()==VoiceEventPublisher.Type.CAPTURE_RESUMED||event.type()==VoiceEventPublisher.Type.CAPTURE_FAILED) {
        devices.invalidateSelection();properties.setDeviceId(null);
      }
    });return devices;
  }
  @Bean VoiceEventPublisher voiceEventPublisher() { return new VoiceEventPublisher(); }
  @Bean WindowsMicrophoneMonitor windowsMicrophoneMonitor() {
    var endpoints=new WindowsAudioEndpoints();return new WindowsMicrophoneMonitor(endpoints::captureEndpoints);
  }
  @Bean VoiceDeliveryService voiceDeliveryService(ShellConversationService shell,Clock clock,VoiceEventPublisher events,VoiceProperties properties) {
    var delivery=new VoiceDeliveryService(shell,clock,events);delivery.setConfirmation(properties.isConfirmation());return delivery;
  }
  @Bean(destroyMethod="close") HttpsVoiceAssetTransport voiceAssetTransport() {return new HttpsVoiceAssetTransport();}
  @Bean(destroyMethod="close") VoiceModelManager voiceModelManager(VoiceProperties properties,HttpsVoiceAssetTransport transport,VoiceEventPublisher events) {
    return new VoiceModelManager(Path.of(properties.getBundleDirectory()),VoiceModelManifest.pinned(),transport,status -> {
      var type=status.state()==VoiceModelManager.State.DOWNLOADING?VoiceEventPublisher.Type.MODEL_PROGRESS:VoiceEventPublisher.Type.MODEL_STATE;
      events.publish(type,status.state()+" "+status.bytes()+"/"+status.totalBytes()+" bytes "+status.asset()+" attempt="+status.attempt()+" "+status.failure());
    });
  }
  @Bean(destroyMethod="close") SherpaBackendFactory sherpaBackendFactory(VoiceModelManager models,VoiceProperties properties) {
    return new SherpaBackendFactory(()-> {try{return models.readyDirectory();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}},properties::inference);
  }
  @Bean(destroyMethod="close") IsolatedVoiceBackendFactory isolatedVoiceBackendFactory(VoiceModelManager models,VoiceProperties properties) {
    return new IsolatedVoiceBackendFactory(()->{try{return models.readyDirectory();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}},properties::inference);
  }
  @Bean(destroyMethod="close") VoiceInputCoordinator voiceInputCoordinator(
      IsolatedVoiceBackendFactory backend,VoiceEventPublisher events,
      VoiceDeliveryService delivery,WindowsMicrophoneMonitor monitor,Clock clock) {
    return new VoiceInputCoordinator(new GuardedMicrophoneCapture(new JavaSoundMicrophoneCapture(),monitor),backend,delivery::accept,events,clock,delivery::targetIsCurrent,System::nanoTime);
  }
}