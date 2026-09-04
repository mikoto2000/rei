package dev.mikoto2000.rei.computer;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ComputerUseService {
  private final UiAutomationBackend uiAutomationBackend;
  private final PhysicalInputBackend physicalInputBackend;
  private final ComputerUseProperties properties;
  private final Map<String, ComputerObservation> observations = new ConcurrentHashMap<>();

  public ComputerUseService(UiAutomationBackend uiAutomationBackend, PhysicalInputBackend physicalInputBackend,
      ComputerUseProperties properties) {
    this.uiAutomationBackend = uiAutomationBackend;
    this.physicalInputBackend = physicalInputBackend;
    this.properties = properties;
  }

  public ComputerObservation observe(ComputerObservationRequest request) {
    ComputerObservation raw = uiAutomationBackend.observe(normalize(request));
    String observationId = UUID.randomUUID().toString();
    List<ComputerElement> elements = new ArrayList<>();
    int index = 1;
    int maxElements = normalize(request).effectiveMaxElements(properties.getMaxElements());
    for (ComputerElement element : raw.elements()) {
      if (index > maxElements) {
        break;
      }
      elements.add(element.withId("e" + index));
      index++;
    }
    ComputerObservation observation = new ComputerObservation(observationId, raw.activeWindow(), raw.windows(), elements);
    observations.put(observationId, observation);
    return observation;
  }

  public ComputerActionResult act(ComputerActionRequest request) {
    if (request == null) {
      return ComputerActionResult.failure("request must not be null", ComputerActionBackend.NONE, false);
    }
    Optional<ComputerElement> element = resolveElement(request.observationId(), request.elementId());
    if (element.isEmpty()) {
      return ComputerActionResult.failure("stale or unknown element: " + request.elementId(), ComputerActionBackend.NONE,
          false);
    }
    return dispatch(request, element.orElseThrow());
  }

  private ComputerActionResult dispatch(ComputerActionRequest request, ComputerElement element) {
    return switch (request.action()) {
      case INVOKE -> invoke(element);
      case SET_VALUE -> semantic(element, ComputerCapability.SET_VALUE,
          () -> uiAutomationBackend.setValue(element, request.text()));
      case TOGGLE -> semantic(element, ComputerCapability.TOGGLE, () -> uiAutomationBackend.toggle(element));
      case FOCUS -> semantic(element, ComputerCapability.FOCUS, () -> uiAutomationBackend.focus(element));
      case CLICK -> safePhysicalClick(element, false);
      case DOUBLE_CLICK -> safePhysicalClick(element, true);
      case TYPE_TEXT -> physicalInputBackend.typeText(request.text() == null ? "" : request.text());
      case KEY_PRESS -> physicalInputBackend.keyPress(request.keyStroke());
      case SCROLL -> physicalInputBackend.scroll(request.wheelAmount() == null ? 0 : request.wheelAmount());
    };
  }

  private ComputerActionResult invoke(ComputerElement element) {
    if (element.hasCapability(ComputerCapability.INVOKE)) {
      ComputerActionResult result = uiAutomationBackend.invoke(element);
      if (result.success()) {
        return result;
      }
      return result;
    }
    return safePhysicalClick(element, false);
  }

  private ComputerActionResult semantic(ComputerElement element, ComputerCapability capability, ActionCall call) {
    if (!element.hasCapability(capability)) {
      return ComputerActionResult.failure("unsupported capability: " + capability, ComputerActionBackend.UI_AUTOMATION,
          false);
    }
    return call.run();
  }

  private ComputerActionResult safePhysicalClick(ComputerElement element, boolean doubleClick) {
    Optional<String> unsafe = unsafePhysicalClickReason(element);
    if (unsafe.isPresent()) {
      return ComputerActionResult.failure(unsafe.orElseThrow(), ComputerActionBackend.ROBOT, true);
    }
    return doubleClick ? physicalInputBackend.doubleClick(element.bounds()) : physicalInputBackend.click(element.bounds());
  }

  private Optional<String> unsafePhysicalClickReason(ComputerElement element) {
    if (!element.hasCapability(ComputerCapability.PHYSICAL_CLICK)) {
      return Optional.of("unsupported capability: PHYSICAL_CLICK");
    }
    if (!element.enabled()) {
      return Optional.of("element is disabled");
    }
    if (element.offscreen()) {
      return Optional.of("element is offscreen");
    }
    if (element.bounds() == null || !element.bounds().valid()) {
      return Optional.of("element bounds are invalid");
    }
    if (!isOnAnyScreen(element.bounds())) {
      return Optional.of("element bounds are outside the screen");
    }
    return Optional.empty();
  }

  private boolean isOnAnyScreen(ComputerBounds bounds) {
    if (GraphicsEnvironment.isHeadless()) {
      return true;
    }
    java.awt.Point center = bounds.center();
    return java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
        .map(device -> device.getDefaultConfiguration().getBounds())
        .anyMatch((Rectangle screen) -> screen.contains(center));
  }

  private Optional<ComputerElement> resolveElement(String observationId, String elementId) {
    ComputerObservation observation = observations.get(observationId);
    if (observation == null) {
      return Optional.empty();
    }
    return observation.elements().stream().filter(element -> element.id().equals(elementId)).findFirst();
  }

  private ComputerObservationRequest normalize(ComputerObservationRequest request) {
    if (request == null) {
      return new ComputerObservationRequest(properties.getMaxDepth(), properties.getMaxElements(),
          properties.isActiveWindowOnly(), properties.isVisibleOnly());
    }
    return request;
  }

  @FunctionalInterface
  private interface ActionCall {
    ComputerActionResult run();
  }
}
