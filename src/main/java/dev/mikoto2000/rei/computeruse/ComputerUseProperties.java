package dev.mikoto2000.rei.computeruse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("rei.computer-use")
public record ComputerUseProperties(@DefaultValue("false") boolean enabled,
    @DefaultValue("20") int maxSteps, @DefaultValue("5") int historyLimit,
    @DefaultValue("500") long stabilizationMillis, @DefaultValue("1") int repairs,
    @DefaultValue("150") long clipboardMillis) {
  public ComputerUseProperties {
    if (maxSteps < 1 || maxSteps > 200 || historyLimit < 1 || historyLimit > 20
        || stabilizationMillis < 1 || stabilizationMillis > 10000 || repairs < 0 || repairs > 2
        || clipboardMillis < 1 || clipboardMillis > 10000) throw new IllegalArgumentException("Invalid Computer Use limits");
  }
}
