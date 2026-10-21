package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.skills.AgentSkillRepository;
import dev.mikoto2000.rei.skills.command.SkillCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** スキルレポートをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/skills")
public final class SkillReportController {
  /** コマンド。 */
  private final SkillCommand command;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** 指定された値や利用先を使って、SkillReportControllerを初期化する。 */
  public SkillReportController(AgentSkillRepository repository) {
    command = new SkillCommand(repository);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping("/report")
  public Text list() {
    return new Text(command.renderList());
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{name}/report")
  public Text show(@PathVariable String name) {
    if (name == null || name.isBlank() || name.length() > 128)
      throw new IllegalArgumentException("Skill name required");
    return new Text(command.renderShow(name));
  }

  /** 変更された設定や記録を反映するため、スキルレポート受付窓口を読み直す。 */
  @PostMapping("/reload-report")
  public Text reload() {
    return new Text(command.renderReload());
  }
}
