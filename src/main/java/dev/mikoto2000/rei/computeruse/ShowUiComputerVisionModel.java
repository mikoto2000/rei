package dev.mikoto2000.rei.computeruse;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** ShowUI only grounds a planner-selected target; it never decides task completion or risk. */
public final class ShowUiComputerVisionModel implements ComputerVisionModel {
  static final int MAX_PIXELS = 1344 * 28 * 28;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShowUiComputerVisionModel.class);
  private final ComputerVisionModel planner;
  private final ChatModel grounding;
  private final Supplier<OpenAiChatOptions.Builder> options;
  private final BooleanSupplier cancelled;

  public ShowUiComputerVisionModel(ComputerVisionModel planner, ChatModel grounding,
      Supplier<OpenAiChatOptions.Builder> options, BooleanSupplier cancelled) {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(grounding);
    this.planner = planner;
    this.grounding = grounding;
    this.options = options;
    this.cancelled = cancelled;
  }

  @Override public ComputerAction decide(ComputerObservation observation) throws Exception {
    checkCancelled();
    // The planner also receives bounded images; geometry and display identities remain unchanged.
    var overview = new CapturedScreen(observation.screenshot().displays().stream()
        .map(d -> new DisplayCapture(d.geometry(), resize(d.image()))).toList());
    var action = planner.decide(new ComputerObservation(observation.goal(), overview,
        observation.recentHistory(), observation.step(), observation.maxSteps()));
    checkCancelled();
    ActionValidator.validate(action, overview);
    var target = action instanceof ComputerAction.Click a ? a.target()
        : action instanceof ComputerAction.DoubleClick a ? a.target() : null;
    if (target == null) return action;
    double confidence = action instanceof ComputerAction.Click a ? a.confidence()
        : ((ComputerAction.DoubleClick) action).confidence();
    if (action.risk() != ComputerAction.Risk.LOW) return action;
    if (confidence < .8) return new ComputerAction.Uncertain("Planner did not identify a confident target");
    var original = observation.screenshot().display(target.displayId());
    var image = resize(original.image());
    var bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", bytes)) throw new IllegalStateException("PNG encoder unavailable");
    var user = UserMessage.builder().text("Locate the UI element described below in this screenshot. "
        + "Return only [x, y], a clickable point with coordinates normalized from 0 to 1 relative to the whole image. "
        + "Target description: " + target.description())
        .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes.toByteArray()))).build();
    var requestOptions = options.get().maxTokens(128).responseFormat(null).toolChoice(null).tools(null)
        .toolCallbacks(List.of()).toolNames(Set.of()).internalToolExecutionEnabled(false).build();
    var response = grounding.call(new Prompt(List.of(user), requestOptions));
    log.info("ShowUI grounding response: step={}, display={}, response={}", observation.step(), target.displayId(), response);
    checkCancelled();
    if (response == null || response.getResults().size() != 1 || response.hasToolCalls()
        || dev.mikoto2000.rei.llm.OutputLimitDetector.isOutputLimitReached(response))
      throw new InvalidComputerDecision("Invalid or truncated ShowUI response");
    var point = parse(response.getResult().getOutput().getText());
    var mapped = new ComputerAction.Target(original.geometry().id(),
        Math.min(original.image().getWidth()-1, (int)(point[0]*original.image().getWidth())),
        Math.min(original.image().getHeight()-1, (int)(point[1]*original.image().getHeight())),
        target.description(), point[0], point[1]);
    ComputerAction result = action instanceof ComputerAction.Click
        ? new ComputerAction.Click(mapped, confidence, action.risk())
        : new ComputerAction.DoubleClick(mapped, confidence, action.risk());
    ActionValidator.validate(result, observation.screenshot());
    return result;
  }

  static double[] parse(String text) {
    try {
      if (text == null || text.length() > 1000) throw new IllegalArgumentException();
      var node = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
      if (node == null || !node.isArray() || node.size() != 2) throw new IllegalArgumentException();
      double[] point = new double[2];
      for (int i=0; i<2; i++) {
        if (!node.get(i).isNumber()) throw new IllegalArgumentException();
        point[i] = node.get(i).doubleValue();
        if (!Double.isFinite(point[i]) || point[i]<0 || point[i]>1) throw new IllegalArgumentException();
      }
      return point;
    } catch (Exception error) {
      throw new InvalidComputerDecision("ShowUI must return exactly two normalized numbers: [x, y]");
    }
  }

  static BufferedImage resize(BufferedImage image) {
    if ((long)image.getWidth()*image.getHeight() <= MAX_PIXELS) return image;
    double scale = Math.sqrt((double)MAX_PIXELS / ((long)image.getWidth()*image.getHeight()));
    var result = new BufferedImage(Math.max(1,(int)(image.getWidth()*scale)),
        Math.max(1,(int)(image.getHeight()*scale)), BufferedImage.TYPE_INT_RGB);
    var graphics = result.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.drawImage(image,0,0,result.getWidth(),result.getHeight(),null);
    } finally { graphics.dispose(); }
    return result;
  }

  private void checkCancelled() {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }
}
