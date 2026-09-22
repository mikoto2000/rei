package dev.mikoto2000.rei.ui.shell;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoundNotificationProperties.class)
public class ShellUiConfiguration {
  @org.springframework.context.annotation.Bean
  dev.mikoto2000.rei.core.completion.CompletionEngine completionEngine(
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.core.completion.CompletionProvider> extensions) {
    var engine = dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completionEngine();
    extensions.orderedStream().forEach(engine::register);
    return engine;
  }
}
