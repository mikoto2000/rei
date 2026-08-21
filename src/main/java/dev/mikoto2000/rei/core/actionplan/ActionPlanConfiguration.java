package dev.mikoto2000.rei.core.actionplan;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Action Plan の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class ActionPlanConfiguration {

  @Bean
  public ActionPlan actionPlan() {
    return new ActionPlan();
  }
}
