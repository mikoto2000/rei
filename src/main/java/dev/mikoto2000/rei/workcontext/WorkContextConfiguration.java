package dev.mikoto2000.rei.workcontext;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkContextProperties.class)
public class WorkContextConfiguration {}
