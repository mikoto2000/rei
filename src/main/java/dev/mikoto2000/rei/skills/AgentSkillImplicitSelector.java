package dev.mikoto2000.rei.skills;

import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** スキルの利用に必要なエージェントスキル暗黙の対象の選択条件を扱う。 */
@Component
public class AgentSkillImplicitSelector implements AgentSkillImplicitSelection {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(AgentSkillImplicitSelector.class);

  /** JSON変換文字列表現。 */
  private static final Pattern JSON_STRING = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** 保存と検索の窓口。 */
  private final AgentSkillRepository repository;

  private final AgentSkillsProperties properties;

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillImplicitSelector(ChatModel chatModel, AgentSkillRepository repository) {
    this(new FixedLlmModelProvider(chatModel), repository);
  }

  /** 指定された値や利用先を使って、AgentSkillImplicitSelectorを初期化する。 */
  public AgentSkillImplicitSelector(
      LlmModelProvider modelProvider, AgentSkillRepository repository) {
    this(modelProvider, repository, new AgentSkillsProperties());
  }

  @Autowired
  public AgentSkillImplicitSelector(
      LlmModelProvider modelProvider,
      AgentSkillRepository repository,
      AgentSkillsProperties properties) {
    this.modelProvider = modelProvider;
    this.repository = repository;
    this.properties = properties;
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  @Override
  public List<AgentSkill> select(
      String prompt, Set<String> excludedSkillNames, List<AgentSkill> providedCandidates) {
    return select(prompt, excludedSkillNames, providedCandidates, null);
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  @Override
  public List<AgentSkill> select(
      String prompt,
      Set<String> excludedSkillNames,
      List<AgentSkill> providedCandidates,
      Runnable beforeModelCall) {
    List<AgentSkill> candidates =
        (providedCandidates == null ? repository.findEnabled() : providedCandidates)
            .stream()
                .sorted(
                    providedCandidates == null
                        ? java.util.Comparator.comparing(AgentSkill::name)
                        : (a, b) -> 0)
                .filter(
                    skill ->
                        excludedSkillNames == null || !excludedSkillNames.contains(skill.name()))
                .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    // Budget exhaustion must stop the run, rather than become a selection fallback.
    if (beforeModelCall != null) beforeModelCall.run();
    var budget =
        beforeModelCall instanceof dev.mikoto2000.rei.llm.ModelCallBudget supplied
                && supplied.tokenLimitEnabled()
            ? supplied
            : null;
    org.springframework.ai.chat.model.ChatResponse response;
    try {
      var model = modelProvider.chatModel(LlmFeature.AGENT_SKILLS);
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
      // Apply feature options on every path; a plain String call uses model defaults instead.
      // Per-request output limits are independent of the optional total-token Run budget.
      var options =
          modelProvider
              .chatOptions(LlmFeature.AGENT_SKILLS, null)
              .mutate()
              .streamUsage(true)
              .toolCallbacks(List.of())
              .toolChoice("none")
              .build();
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
      var request =
          new org.springframework.ai.chat.prompt.Prompt(
              buildSelectionPrompt(prompt, candidates), options);
      var aggregate =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      // Timeout the completion Mono, not each chunk: periodic thinking must not reset the deadline.
      // Cancelling block() also cancels the underlying HTTP stream on Run interruption.
      new org.springframework.ai.chat.model.MessageAggregator()
          .aggregate(reactor.core.publisher.Flux.defer(() -> model.stream(request)), aggregate::set)
          .then()
          .timeout(properties.getSelectorTimeout())
          .block();
      response = aggregate.get();
    } catch (Exception error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      if (budget != null) budget.recordTotalTokens(null);
      log.debug("Agent Skill implicit selection failed", error);
      return List.of();
    }
    if (budget != null) {
      var usage = response == null ? null : response.getMetadata().getUsage();
      budget.recordTotalTokens(usage == null ? null : usage.getTotalTokens());
    }
    String content =
        response == null || response.getResult() == null
            ? ""
            : response.getResult().getOutput().getText();
    return resolveSelectedSkills(parseJsonStringArray(content), candidates);
  }

  /** 利用先に渡せるよう、選択内容プロンプトを組み立てる。 */
  private String buildSelectionPrompt(String userPrompt, List<AgentSkill> candidates) {
    StringBuilder builder = new StringBuilder();
    builder.append(
        """
        次のユーザー依頼に役立つ Agent Skill を選んでください。
        該当なしの場合は空配列を返してください。
        必ず JSON 配列のみを返してください。

        Skills:
        """);
    for (AgentSkill skill : candidates) {
      builder.append("- name: ").append(skill.name()).append('\n');
      builder.append("  description: ").append(skill.description()).append('\n');
    }
    builder.append("\nReturn format:\n[\"skill-name\"]\n");
    builder.append("\nUser request:\n").append(userPrompt == null ? "" : userPrompt).append("\n");
    return builder.toString();
  }

  /** 入力を機能内で扱えるよう、JSON変換文字列表現Arrayを解釈する。 */
  private List<String> parseJsonStringArray(String content) {
    String text = content == null ? "" : content.strip();
    if (!text.startsWith("[") || !text.endsWith("]")) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    Matcher matcher = JSON_STRING.matcher(text);
    while (matcher.find()) {
      names.add(unescape(matcher.group(1)));
    }
    return names;
  }

  /** スキルの利用で用いるunescapeを返す。 */
  private String unescape(String value) {
    return value.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  /** 指定や設定に応じた対象を利用するため、選択中スキル一覧を確定する。 */
  private List<AgentSkill> resolveSelectedSkills(List<String> names, List<AgentSkill> candidates) {
    Set<String> uniqueNames = new LinkedHashSet<>(names);
    List<AgentSkill> selected = new ArrayList<>();
    for (String name : uniqueNames) {
      candidates.stream()
          .filter(skill -> skill.name().equals(name))
          .findFirst()
          .ifPresent(selected::add);
    }
    return List.copyOf(selected);
  }

  /** 固定言語モデルモデルを用途や設定に応じて提供する窓口。 */
  private static class FixedLlmModelProvider extends LlmModelProvider {
    /** 応答生成に使うモデル。 */
    private final ChatModel chatModel;

    /** 指定された値や利用先を使って、FixedLlmModelProviderを初期化する。 */
    FixedLlmModelProvider(ChatModel chatModel) {
      super(chatModel, new dev.mikoto2000.rei.llm.LlmProperties());
      this.chatModel = chatModel;
    }

    /** スキルの利用で用いる応答生成に使うモデルを返す。 */
    @Override
    public ChatModel chatModel(String feature) {
      return chatModel;
    }
  }
}
