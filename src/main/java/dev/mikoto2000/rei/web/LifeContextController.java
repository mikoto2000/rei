package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.life.LifeContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 既存 Bearer 認証内の明示的な設定操作。モデル用ツールには登録しない。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/life")
public final class LifeContextController {
  private final org.springframework.beans.factory.ObjectProvider<LifeContext> lives;

  private LifeContext life() {
    return lives.getObject();
  }

  public LifeContextController(
      org.springframework.beans.factory.ObjectProvider<LifeContext> lives) {
    this.lives = lives;
  }

  public record Configure(LifeContext.Settings settings) implements StrictApiRequest {}

  @GetMapping("/settings")
  public LifeContext.Settings settings() {
    return life().settings();
  }

  @GetMapping("/status")
  public LifeContext.Status status() {
    return life().status();
  }

  @PutMapping("/settings")
  public LifeContext.Settings configure(@RequestBody Configure request) {
    return life().configure(request.settings());
  }
}
