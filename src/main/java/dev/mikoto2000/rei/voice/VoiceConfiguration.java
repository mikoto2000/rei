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
  @Bean(destroyMethod="close") SherpaBackendFactory sherpaBackendFactory(VoiceProperties properties) {
    return new SherpaBackendFactory(()->Path.of(properties.getBundleDirectory()));
  }
  @Bean(destroyMethod="close") VoiceInputCoordinator voiceInputCoordinator(
      SherpaBackendFactory backend,AudioDeviceService devices,VoiceEventPublisher events,
      ShellConversationService conversations,Clock clock) {
    return new VoiceInputCoordinator(new JavaSoundMicrophoneCapture(),backend,conversations::submit,events,clock);
  }
}