package dev.mikoto2000.rei.workcontext;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration
@EnableConfigurationProperties(WorkContextProperties.class)
public class WorkContextConfiguration {}
