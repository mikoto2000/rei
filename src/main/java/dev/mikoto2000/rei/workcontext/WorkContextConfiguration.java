package dev.mikoto2000.rei.workcontext;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 作業文脈の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
@EnableConfigurationProperties(WorkContextProperties.class)
public class WorkContextConfiguration {}
