package dev.mikoto2000.rei.computer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComputerUseProperties.class)
public class ComputerUseConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "rei.computer-use", name = "enabled", havingValue = "true")
  UiAutomationBackend uiAutomationBackend() {
    return new WindowsUiAutomationBackend();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "rei.computer-use", name = "enabled", havingValue = "true")
  PhysicalInputBackend physicalInputBackend() {
    return new RobotPhysicalInputBackend();
  }

  @Bean
  @ConditionalOnProperty(prefix = "rei.computer-use", name = "enabled", havingValue = "true")
  ComputerUseService computerUseService(UiAutomationBackend uiAutomationBackend,
      PhysicalInputBackend physicalInputBackend, ComputerUseProperties properties) {
    return new ComputerUseService(uiAutomationBackend, physicalInputBackend, properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = "rei.computer-use", name = "enabled", havingValue = "true")
  ComputerUseTools computerUseTools(ComputerUseService service) {
    return new ComputerUseTools(service);
  }
}
