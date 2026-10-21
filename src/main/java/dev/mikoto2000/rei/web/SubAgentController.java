package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.subagent.SubAgentCommand;
import dev.mikoto2000.rei.subagent.SubAgentDefinitionLoader;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import dev.mikoto2000.rei.subagent.SubAgentToolPolicy;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 補助エージェントをWeb APIの操作として受け付ける。
 *
 * <p>Fixed definition management only; this API does not launch or delegate to an agent.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/subagents")
public final class SubAgentController {
  /** コマンド。 */
  private final SubAgentCommand command;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す識別情報を表す。
   *
   * @param id 識別子
   */
  public record Identity(String id) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すファイル要求を表す。
   *
   * @param file ファイル
   */
  public record FileRequest(String file) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、SubAgentControllerを初期化する。 */
  public SubAgentController(
      SubAgentRegistry registry, SubAgentDefinitionLoader loader, SubAgentToolPolicy policy) {
    command = new SubAgentCommand(registry, loader, policy);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Text list() {
    return new Text(command.renderList());
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public Text show(@PathVariable String id) {
    return new Text(command.renderShow(id));
  }

  /** 変更された設定や記録を反映するため、補助エージェント受付窓口を読み直す。 */
  @PostMapping("/reload")
  public Text reload() {
    return new Text(command.renderReload());
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  @PostMapping("/validate")
  public Text validate(@RequestBody FileRequest request) {
    if (request == null
        || request.file() == null
        || request.file().isBlank()
        || request.file().length() > 4096
        || request.file().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Definition file required");
    return new Text(command.renderValidate(Path.of(request.file())));
  }

  /** Web APIによる操作で用いる初期化を返す。 */
  @PostMapping("/initialize")
  public Text initialize(@RequestBody Identity request) {
    if (request == null || request.id() == null || request.id().length() > 128)
      throw new IllegalArgumentException("Definition identity required");
    return new Text(command.renderInitialize(request.id()));
  }
}
