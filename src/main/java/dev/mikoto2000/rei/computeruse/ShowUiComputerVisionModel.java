package dev.mikoto2000.rei.computeruse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

/**
 * 画面操作の支援に必要な表示表示画面操作画像認識モデルを扱う。
 *
 * <p>Shared grounding workflow; model-specific wire formats are selected explicitly.
 */
public final class ShowUiComputerVisionModel implements ComputerVisionModel {
  /** 最大画素数。 */
  static final int MAX_PIXELS = 1344 * 28 * 28;

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ShowUiComputerVisionModel.class);

  /** 計画の作成。 */
  private final ComputerVisionModel planner;

  /** 操作対象の画面位置を判断するモデル。 */
  private final ChatModel grounding;

  /** 選択可能な設定。 */
  private final Supplier<OpenAiChatOptions.Builder> options;

  /** キャンセル済み。 */
  private final BooleanSupplier cancelled;

  /** 通信規約。 */
  private final GroundingProtocol protocol;

  /** 確認の窓口。 */
  private final GroundingVerifier verifier;

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ShowUiComputerVisionModel(
      ComputerVisionModel planner,
      ChatModel grounding,
      Supplier<OpenAiChatOptions.Builder> options,
      BooleanSupplier cancelled) {
    this(planner, grounding, options, cancelled, GroundingProtocol.SHOWUI);
  }

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ShowUiComputerVisionModel(
      ComputerVisionModel planner,
      ChatModel grounding,
      Supplier<OpenAiChatOptions.Builder> options,
      BooleanSupplier cancelled,
      GroundingProtocol protocol) {
    this(
        planner,
        grounding,
        options,
        cancelled,
        protocol,
        (o, i, t, p) -> {
          throw new InvalidComputerDecision("Grounding verifier is required before clicking");
        });
  }

  /** 指定された値や利用先を使って、ShowUiComputerVisionModelを初期化する。 */
  ShowUiComputerVisionModel(
      ComputerVisionModel planner,
      ChatModel grounding,
      Supplier<OpenAiChatOptions.Builder> options,
      BooleanSupplier cancelled,
      GroundingProtocol protocol,
      GroundingVerifier verifier) {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(grounding);
    this.planner = planner;
    this.grounding = grounding;
    this.options = options;
    this.cancelled = cancelled;
    this.protocol = protocol;
    this.verifier = java.util.Objects.requireNonNull(verifier);
  }

  /** 画面操作の支援で用いる判断を返す。 */
  @Override
  public ComputerAction decide(ComputerObservation observation) throws Exception {
    checkCancelled();
    // The planner also receives bounded images; geometry and display identities remain unchanged.
    var overview =
        new CapturedScreen(
            observation.screenshot().displays().stream()
                .map(d -> new DisplayCapture(d.geometry(), resize(d.image())))
                .toList());
    var action =
        planner.decide(
            new ComputerObservation(
                observation.goal(),
                overview,
                observation.recentHistory(),
                observation.step(),
                observation.maxSteps(),
                observation.diagnosticRun(),
                observation.focusState()));
    checkCancelled();
    ActionValidator.validate(action, overview);
    var target =
        action instanceof ComputerAction.Click a
            ? a.target()
            : action instanceof ComputerAction.DoubleClick a ? a.target() : null;
    if (target == null) return action;
    double confidence =
        action instanceof ComputerAction.Click a
            ? a.confidence()
            : ((ComputerAction.DoubleClick) action).confidence();
    // Confirmation is an execution policy, not a reason to return ungrounded coordinates.
    // Preserve the risk through localization; the service still decides whether execution is
    // allowed.
    if (action.risk() == ComputerAction.Risk.PROHIBITED) return action;
    if (confidence < .8)
      return new ComputerAction.Uncertain("Planner did not identify a confident target");
    var original = observation.screenshot().display(target.displayId());
    var source = original.image();
    var coarse = locate(observation, original, target, source, "", 0, 0);
    checkCancelled();
    int width = Math.max(1, source.getWidth() / 2), height = Math.max(1, source.getHeight() / 2);
    int left =
        Math.max(
            0,
            Math.min(source.getWidth() - width, (int) (coarse[0] * source.getWidth()) - width / 2));
    int top =
        Math.max(
            0,
            Math.min(
                source.getHeight() - height, (int) (coarse[1] * source.getHeight()) - height / 2));
    var crop = source.getSubimage(left, top, width, height);
    verifier.verify(observation, resize(crop), target.description(), null);
    checkCancelled();
    var fine = locate(observation, original, target, crop, "-refinement", left, top);
    checkCancelled();
    double x = (left + fine[0] * width) / source.getWidth(),
        y = (top + fine[1] * height) / source.getHeight();
    // Verify against the whole selected display, preserving context lost in the crop.
    verifier.verifyPoint(observation, original, target.description(), new double[] {x, y});
    checkCancelled();
    var mapped =
        new ComputerAction.Target(
            original.geometry().id(),
            left + Math.min(width - 1, (int) (fine[0] * width)),
            top + Math.min(height - 1, (int) (fine[1] * height)),
            target.description(),
            x,
            y);
    ComputerAction result =
        action instanceof ComputerAction.Click
            ? new ComputerAction.Click(mapped, confidence, action.risk())
            : new ComputerAction.DoubleClick(mapped, confidence, action.risk());
    ActionValidator.validate(result, observation.screenshot());
    return result;
  }

  /** 画面操作の支援で用いる場所の特定を返す。 */
  private double[] locate(
      ComputerObservation observation,
      DisplayCapture original,
      ComputerAction.Target target,
      BufferedImage region,
      String stage,
      int left,
      int top)
      throws Exception {
    checkCancelled();
    var prefix = protocol.id + stage;
    var image = resize(region);
    var bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", bytes))
      throw new IllegalStateException("PNG encoder unavailable");
    var user =
        UserMessage.builder()
            .text(protocol.prompt(target.description()))
            .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes.toByteArray())))
            .build();
    var requestBuilder =
        options
            .get()
            .maxCompletionTokens(null)
            .maxTokens(128)
            .responseFormat(null)
            .toolChoice(null)
            .toolCallbacks(List.of());
    if (protocol == GroundingProtocol.UITARS) requestBuilder.frequencyPenalty(1.0);
    var requestOptions = requestBuilder.build();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(requestOptions);
    saveDiagnostics(
        observation,
        directory -> {
          java.nio.file.Files.write(directory.resolve(prefix + "-input.png"), bytes.toByteArray());
          var details = new java.util.LinkedHashMap<String, Object>();
          details.put("displayId", original.geometry().id());
          details.put("grounding", protocol.id);
          details.put("stage", stage.isEmpty() ? "overview" : "refinement");
          details.put("cropLeft", left);
          details.put("cropTop", top);
          details.put("cropWidth", region.getWidth());
          details.put("cropHeight", region.getHeight());
          details.put("sourceWidth", original.image().getWidth());
          details.put("sourceHeight", original.image().getHeight());
          details.put("sentWidth", image.getWidth());
          details.put("sentHeight", image.getHeight());
          details.put("targetDescription", target.description());
          details.put("prompt", user.getText());
          if (protocol == GroundingProtocol.SHOWUI)
            details.put("contentOrder", List.of("instruction", "image", "targetDescription"));
          details.put("model", requestOptions.getModel());
          details.put("maxTokens", requestOptions.getMaxTokens());
          new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValue(directory.resolve(prefix + "-request.json").toFile(), details);
        });
    checkCancelled();
    org.springframework.ai.chat.model.ChatResponse response;
    try {
      response = grounding.call(new Prompt(List.of(user), requestOptions));
    } catch (RuntimeException error) {
      saveDiagnostics(
          observation,
          directory -> {
            var stack = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(stack));
            java.nio.file.Files.writeString(
                directory.resolve(prefix + "-error.txt"), stack.toString());
          });
      throw error;
    }
    saveDiagnostics(
        observation,
        directory -> {
          // Preserve model text before validation, including malformed coordinates or truncated
          // output.
          var details = new java.util.LinkedHashMap<String, Object>();
          details.put("response", response == null ? null : response.toString());
          details.put(
              "texts",
              response == null
                  ? null
                  : response.getResults().stream().map(g -> g.getOutput().getText()).toList());
          new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValue(directory.resolve(prefix + "-response.json").toFile(), details);
        });
    log.info(
        "{} grounding response: step={}, display={}, response={}",
        prefix,
        observation.step(),
        target.displayId(),
        response);
    checkCancelled();
    if (response == null
        || response.getResults().size() != 1
        || response.hasToolCalls()
        || dev.mikoto2000.rei.llm.OutputLimitDetector.isOutputLimitReached(response))
      throw new InvalidComputerDecision("Invalid or truncated " + protocol.id + " response");
    return protocol.parse(response.getResult().getOutput().getText());
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  static double[] parse(String text) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (text == null || text.length() > 1000) throw new IllegalArgumentException();
      var node =
          new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
      if (node == null || !node.isArray() || node.size() != 2) throw new IllegalArgumentException();
      double[] point = new double[2];
      for (int i = 0; i < 2; i++) {
        if (!node.get(i).isNumber()) throw new IllegalArgumentException();
        point[i] = node.get(i).doubleValue();
        if (!Double.isFinite(point[i]) || point[i] < 0 || point[i] > 1)
          throw new IllegalArgumentException();
      }
      return point;
    } catch (Exception error) {
      throw new InvalidComputerDecision(
          "ShowUI must return exactly two normalized numbers: [x, y]");
    }
  }

  /** 画面操作の支援に必要な診断書き込みを扱う。 */
  @FunctionalInterface
  private interface DiagnosticWrite {
    /** 指定された内容を書き込み先へ反映する。 */
    void write(java.nio.file.Path directory) throws Exception;
  }

  /** 後から参照できるよう、診断情報を保存する。 */
  private static void saveDiagnostics(ComputerObservation observation, DiagnosticWrite write) {
    if (observation.diagnosticRun() == null) return;
    try {
      var directory =
          java.nio.file.Files.createDirectories(
              observation.diagnosticRun().resolve("step-%03d".formatted(observation.step())));
      write.write(directory);
    } catch (Exception error) {
      log.warn("Could not save grounding diagnostics: step={}", observation.step(), error);
    }
  }

  /** 画面操作の支援で用いるresizeを返す。 */
  static BufferedImage resize(BufferedImage image) {
    if ((long) image.getWidth() * image.getHeight() <= MAX_PIXELS) return image;
    double scale = Math.sqrt((double) MAX_PIXELS / ((long) image.getWidth() * image.getHeight()));
    var result =
        new BufferedImage(
            Math.max(1, (int) (image.getWidth() * scale)),
            Math.max(1, (int) (image.getHeight() * scale)),
            BufferedImage.TYPE_INT_RGB);
    var graphics = result.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
    } finally {
      graphics.dispose();
    }
    return result;
  }

  /** 後続の操作を進められるか判断するため、キャンセル済みを確認する。 */
  private void checkCancelled() {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }
}
