package dev.mikoto2000.rei.ui.shell;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoundNotificationProperties.class)
public class ShellUiConfiguration {
}
