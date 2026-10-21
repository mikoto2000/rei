package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 音声の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VoiceProperties.class, VoiceCorrectionProperties.class})
public class VoiceConfiguration {
  /** 音声による対話で用いる音声補正サービスを返す。 */
  @Bean(destroyMethod = "close")
  VoiceCorrectionService voiceCorrectionService(
      VoiceCorrectionProperties properties,
      dev.mikoto2000.rei.llm.LlmChatClientProvider clients,
      dev.mikoto2000.rei.llm.LlmModelProvider models,
      dev.mikoto2000.rei.conversation.ConversationLogStore history,
      VoiceEventPublisher events) {
    var context = new VoiceCorrectionContext(history, null, properties);
    var llm = new VoiceCorrectionLlm(clients, models, properties);
    var service = new VoiceCorrectionService(properties, context::capture, llm::call, events);
    events.subscribe(
        event -> {
          if (event.type() == VoiceEventPublisher.Type.STATE_CHANGED
              && !event.detail().equals("LISTENING")
              && !event.detail().equals("STARTING")) service.cancelAll();
        });
    return service;
  }

  /** 音声による対話で用いる音声音声実行可否の判断を返す。 */
  @Bean
  VoiceAudioGate voiceAudioGate() {
    return new VoiceAudioGate(System::nanoTime);
  }

  /** 音声による対話で用いるsapi音声出力を返す。 */
  @Bean
  SapiVoiceOutput sapiVoiceOutput() {
    return new SapiVoiceOutput();
  }

  /** 音声による対話で用いる音声再生サービスを返す。 */
  @Bean(destroyMethod = "close")
  VoicePlaybackService voicePlaybackService(
      SapiVoiceOutput output,
      VoiceAudioGate gate,
      VoiceProperties properties,
      VoiceInputCoordinator voice,
      VoiceDeliveryService delivery,
      VoiceEventPublisher events) {
    var playback =
        new VoicePlaybackService(
            output,
            gate,
            properties::advanced,
            context ->
                voice.state() == VoiceInputCoordinator.State.LISTENING && delivery.ownsRun(context),
            events);
    events.subscribe(
        event -> {
          if (event.type() == VoiceEventPublisher.Type.STATE_CHANGED
              && !event.detail().equals("LISTENING")
              && !event.detail().equals("STARTING")) playback.stop();
        });
    return playback;
  }

  /** 音声による対話で用いる音声Deviceサービスを返す。 */
  @Bean
  AudioDeviceService audioDeviceService(VoiceProperties properties, VoiceEventPublisher events) {
    var devices = new AudioDeviceService();
    devices.restoreSelection(properties.getDeviceId());
    events.subscribe(
        event -> {
          if (event.type() == VoiceEventPublisher.Type.DEVICE_CHANGED
              || event.type() == VoiceEventPublisher.Type.CAPTURE_RESUMED
              || event.type() == VoiceEventPublisher.Type.CAPTURE_FAILED) {
            devices.invalidateSelection();
            properties.setDeviceId(null);
          }
        });
    return devices;
  }

  /** 音声による対話で用いる音声実行状況の通知先を返す。 */
  @Bean
  VoiceEventPublisher voiceEventPublisher() {
    return new VoiceEventPublisher();
  }

  /** 音声による対話で用いるWindowsマイク排他制御を返す。 */
  @Bean
  WindowsMicrophoneMonitor windowsMicrophoneMonitor() {
    var endpoints = new WindowsAudioEndpoints();
    return new WindowsMicrophoneMonitor(endpoints::captureEndpoints);
  }

  /** 音声による対話で用いる音声配信サービスを返す。 */
  @Bean
  VoiceDeliveryService voiceDeliveryService(
      ShellConversationService shell,
      Clock clock,
      VoiceEventPublisher events,
      VoiceProperties properties,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.application.run.RunService>
          runs,
      dev.mikoto2000.rei.core.service.CommandCancellationService cancellation) {
    shell.onActiveCancellation(
        context -> {
          var runner = runs.getIfAvailable();
          if (runner != null)
            try {
              return runner.cancel(context.runId()).accepted();
            } catch (dev.mikoto2000.rei.application.run.RunNotFoundException missing) {
              /* Legacy unregistered Run. */
            }
          return cancellation.cancelRegisteredRun(context.runId());
        });
    var delivery = new VoiceDeliveryService(shell, clock, events, properties::advanced);
    delivery.setConfirmation(properties.isConfirmation());
    return delivery;
  }

  /** 音声による対話で用いる音声素材通信経路を返す。 */
  @Bean(destroyMethod = "close")
  HttpsVoiceAssetTransport voiceAssetTransport() {
    return new HttpsVoiceAssetTransport();
  }

  /** 音声による対話で用いる音声モデル管理を返す。 */
  @Bean(destroyMethod = "close")
  VoiceModelManager voiceModelManager(
      VoiceProperties properties, HttpsVoiceAssetTransport transport, VoiceEventPublisher events) {
    return new VoiceModelManager(
        Path.of(properties.getBundleDirectory()),
        VoiceModelManifest.pinned(),
        transport,
        status -> {
          var type =
              status.state() == VoiceModelManager.State.DOWNLOADING
                  ? VoiceEventPublisher.Type.MODEL_PROGRESS
                  : VoiceEventPublisher.Type.MODEL_STATE;
          events.publish(
              type,
              status.state()
                  + " "
                  + status.bytes()
                  + "/"
                  + status.totalBytes()
                  + " bytes "
                  + status.asset()
                  + " attempt="
                  + status.attempt()
                  + " "
                  + status.failure());
        });
  }

  /** 音声による対話で用いるsherpaバックエンド生成元を返す。 */
  @Bean(destroyMethod = "close")
  SherpaBackendFactory sherpaBackendFactory(VoiceModelManager models, VoiceProperties properties) {
    return new SherpaBackendFactory(
        () -> {
          try {
            return models.readyDirectory();
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        },
        properties::inference);
  }

  /** 音声による対話で用いる独立した音声バックエンド生成元を返す。 */
  @Bean(destroyMethod = "close")
  IsolatedVoiceBackendFactory isolatedVoiceBackendFactory(
      VoiceModelManager models, VoiceProperties properties) {
    return new IsolatedVoiceBackendFactory(
        () -> {
          try {
            return models.readyDirectory();
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        },
        properties::inference);
  }

  /** 音声による対話で用いる音声入力からの依頼かどうか処理の調整を返す。 */
  @Bean(destroyMethod = "close")
  VoiceInputCoordinator voiceInputCoordinator(
      IsolatedVoiceBackendFactory backend,
      VoiceEventPublisher events,
      VoiceDeliveryService delivery,
      WindowsMicrophoneMonitor monitor,
      Clock clock,
      VoiceAudioGate audioGate,
      VoiceProperties properties,
      VoiceCorrectionService correction) {
    var coordinator =
        new VoiceInputCoordinator(
            new GuardedMicrophoneCapture(new JavaSoundMicrophoneCapture(), monitor),
            backend,
            delivery::accept,
            events,
            clock,
            delivery::targetIsCurrent,
            System::nanoTime,
            audioGate,
            properties::advanced);
    coordinator.configureCorrection(correction, delivery::requireReview);
    delivery.onSubmitted(correction::submitted);
    return coordinator;
  }
}
