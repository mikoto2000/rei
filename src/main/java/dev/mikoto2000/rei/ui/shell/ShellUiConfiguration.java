package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** シェル表示の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoundNotificationProperties.class)
public class ShellUiConfiguration {
  /** ターミナルでの対話で用いる完了条件Engineを返す。 */
  @org.springframework.context.annotation.Bean
  dev.mikoto2000.rei.core.completion.CompletionEngine completionEngine(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.core.completion.CompletionProvider>
          extensions) {
    var engine = dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completionEngine();
    extensions.orderedStream().forEach(engine::register);
    return engine;
  }
}
