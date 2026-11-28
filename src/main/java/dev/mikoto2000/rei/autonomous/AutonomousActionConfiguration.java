package dev.mikoto2000.rei.autonomous;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Binds draft settings; ordinary component scanning supplies the concrete feature adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutonomousActionProperties.class)
@Import(AutonomousGenerationService.class)
public class AutonomousActionConfiguration {}
