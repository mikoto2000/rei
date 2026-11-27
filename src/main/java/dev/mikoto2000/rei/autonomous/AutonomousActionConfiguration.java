package dev.mikoto2000.rei.autonomous;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Installs the draft foundation without registering generation or publication tools. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutonomousActionProperties.class)
@Import({AutonomousDraftStore.class, AutonomousDraftLifecycle.class})
public class AutonomousActionConfiguration {}
