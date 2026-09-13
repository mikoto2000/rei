package dev.mikoto2000.rei.computeruse;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Explicit startup opt-in; intentionally not bound to YAML or environment variables. */
@Component
public final class FullAutoOptions implements ApplicationRunner {
  private final boolean enabled;
  public FullAutoOptions(ApplicationArguments args) {
    var values=args.getOptionValues("fullauto");
    if (!args.containsOption("fullauto")) enabled=false;
    else if (values==null || values.isEmpty()) enabled=true;
    else if (values.size()==1 && ("true".equals(values.getFirst()) || "false".equals(values.getFirst())))
      enabled=Boolean.parseBoolean(values.getFirst());
    else throw new IllegalArgumentException("Use --fullauto, --fullauto=true, or --fullauto=false");
  }
  public boolean enabled() { return enabled; }
  public String startupMessage() {
    return enabled ? "[computer_use] --fullauto enabled: CONFIRM_REQUIRED operations (投稿・送信・削除・購入など) are allowed; PROHIBITED remains blocked."
        : "[computer_use] --fullauto disabled: only LOW operations are allowed.";
  }
  @Override public void run(ApplicationArguments args) { System.out.println(startupMessage()); }
}
