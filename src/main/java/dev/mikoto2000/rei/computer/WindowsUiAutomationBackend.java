package dev.mikoto2000.rei.computer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class WindowsUiAutomationBackend implements UiAutomationBackend {
  private final Object automation;

  public WindowsUiAutomationBackend() {
    this.automation = createAutomation();
  }

  WindowsUiAutomationBackend(Object automation) {
    this.automation = automation;
  }

  @Override
  public ComputerObservation observe(ComputerObservationRequest request) {
    ComputerObservationRequest effectiveRequest = normalize(request);
    if (automation == null) {
      return new ComputerObservation("raw", Optional.empty(), List.of(), List.of());
    }
    try {
      List<?> rawWindows = asList(callAny(automation, "getDesktopWindows"));
      ComputerBounds focusedBounds = bounds(callAny(automation, "getFocusedElement"));
      Object activeRawWindow = activeWindow(rawWindows, focusedBounds);
      ComputerWindow activeWindow = activeRawWindow == null ? null : toWindow(activeRawWindow, true);
      List<ComputerWindow> windows = new ArrayList<>();
      int index = 1;
      for (Object rawWindow : rawWindows) {
        boolean active = rawWindow == activeRawWindow;
        ComputerWindow candidate = toWindow(rawWindow, active);
        windows.add(new ComputerWindow("w" + index, candidate.title(), candidate.className(), candidate.bounds(),
            candidate.active(), candidate.focused()));
        index++;
      }
      List<?> targetWindows = effectiveRequest.effectiveActiveWindowOnly(true)
          ? (activeRawWindow == null ? List.of() : List.of(activeRawWindow))
          : rawWindows;
      List<ComputerElement> elements = childElements(targetWindows, effectiveRequest);
      return new ComputerObservation("raw", Optional.ofNullable(activeWindow), windows, elements);
    } catch (RuntimeException e) {
      return new ComputerObservation("raw", Optional.empty(), List.of(), List.of());
    }
  }

  @Override
  public ComputerActionResult invoke(ComputerElement element) {
    Object target = findCurrentElement(element);
    if (target == null) {
      return ComputerActionResult.failure("UIA target not found", ComputerActionBackend.UI_AUTOMATION, false);
    }
    Object result = callAny(target, "invoke", "click");
    return result == FAILED ? ComputerActionResult.failure("UIA invoke failed", ComputerActionBackend.UI_AUTOMATION,
        false) : ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
  }

  @Override
  public ComputerActionResult setValue(ComputerElement element, String value) {
    Object target = findCurrentElement(element);
    if (target == null) {
      return ComputerActionResult.failure("UIA target not found", ComputerActionBackend.UI_AUTOMATION, false);
    }
    Object result = callAny(target, new Class<?>[] {String.class}, new Object[] {value}, "setText", "setValue");
    return result == FAILED ? ComputerActionResult.failure("UIA setValue failed", ComputerActionBackend.UI_AUTOMATION,
        false) : ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
  }

  @Override
  public ComputerActionResult toggle(ComputerElement element) {
    Object target = findCurrentElement(element);
    if (target == null) {
      return ComputerActionResult.failure("UIA target not found", ComputerActionBackend.UI_AUTOMATION, false);
    }
    Object result = callAny(target, "toggle");
    return result == FAILED ? ComputerActionResult.failure("UIA toggle failed", ComputerActionBackend.UI_AUTOMATION,
        false) : ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
  }

  @Override
  public ComputerActionResult focus(ComputerElement element) {
    Object target = findCurrentElement(element);
    if (target == null) {
      return ComputerActionResult.failure("UIA target not found", ComputerActionBackend.UI_AUTOMATION, false);
    }
    Object result = callAny(target, "focus", "setFocus");
    if (result == FAILED) {
      Object rawElement = callAny(target, "getElement");
      result = callAny(rawElement, "setFocus");
    }
    return result == FAILED ? ComputerActionResult.failure("UIA focus failed", ComputerActionBackend.UI_AUTOMATION,
        false) : ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
  }

  private List<ComputerElement> childElements(List<?> windows, ComputerObservationRequest request) {
    int max = request.effectiveMaxElements(80);
    List<ComputerElement> elements = new ArrayList<>();
    for (Object window : windows) {
      if (elements.size() >= max) {
        break;
      }
      collectElements(window, request.effectiveMaxDepth(3), request.effectiveVisibleOnly(true), max, elements);
    }
    return elements;
  }

  private void collectElements(Object parent, int remainingDepth, boolean visibleOnly, int max,
      List<ComputerElement> elements) {
    if (remainingDepth <= 0 || elements.size() >= max) {
      return;
    }
    Object children = callAny(parent, new Class<?>[] {boolean.class}, new Object[] {false}, "getChildren");
    if (children instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        if (elements.size() >= max) {
          break;
        }
        ComputerElement element = toElement(child);
        if (!visibleOnly || !element.offscreen()) {
          elements.add(element);
        }
        collectElements(child, remainingDepth - 1, visibleOnly, max, elements);
      }
    }
  }

  private ComputerWindow toWindow(Object window, boolean active) {
    return new ComputerWindow(null, text(window, "getName", "getTitle", "name"), text(window, "getClassName"),
        bounds(window), active, active);
  }

  private ComputerElement toElement(Object element) {
    String role = text(element, "getControlType", "controlType");
    EnumSet<ComputerCapability> capabilities = capabilities(role);
    ComputerBounds bounds = bounds(element);
    if (bounds != null && bounds.valid()) {
      capabilities.add(ComputerCapability.PHYSICAL_CLICK);
    }
    Object rawElement = callAny(element, "getElement");
    Object metadataSource = rawElement == null ? element : rawElement;
    return new ComputerElement(null, normalize(role), text(element, "getName", "name"), text(element, "getValue"),
        text(element, "getAutomationId"), text(element, "getClassName"), bounds,
        bool(element, true, "isEnabled", "getIsEnabled"), bool(metadataSource, false, "hasKeyboardFocus", "getHasKeyboardFocus"),
        bool(element, false, "isOffScreen", "isOffscreen", "offScreen", "getIsOffscreen"), capabilities);
  }

  private EnumSet<ComputerCapability> capabilities(String role) {
    String normalized = normalize(role);
    EnumSet<ComputerCapability> capabilities = EnumSet.noneOf(ComputerCapability.class);
    if (normalized.contains("button") || normalized.contains("hyperlink") || normalized.contains("menu")) {
      capabilities.add(ComputerCapability.INVOKE);
    }
    if (normalized.contains("edit") || normalized.contains("document")) {
      capabilities.add(ComputerCapability.SET_VALUE);
      capabilities.add(ComputerCapability.FOCUS);
    }
    if (normalized.contains("checkbox")) {
      capabilities.add(ComputerCapability.TOGGLE);
    }
    if (normalized.contains("list") || normalized.contains("item") || normalized.contains("radio")) {
      capabilities.add(ComputerCapability.SELECT);
    }
    if (normalized.contains("combo") || normalized.contains("tree")) {
      capabilities.add(ComputerCapability.EXPAND);
      capabilities.add(ComputerCapability.COLLAPSE);
    }
    if (normalized.contains("scroll")) {
      capabilities.add(ComputerCapability.SCROLL);
    }
    if (normalized.contains("slider") || normalized.contains("progress")) {
      capabilities.add(ComputerCapability.SET_RANGE_VALUE);
    }
    return capabilities;
  }

  private ComputerBounds bounds(Object target) {
    Object value = callAny(target, "getBoundingRectangle", "boundingRectangle");
    if (value == null || value == FAILED) {
      return null;
    }
    Integer x = number(value, "getLeft", "left", "x");
    Integer y = number(value, "getTop", "top", "y");
    Integer right = number(value, "getRight", "right");
    Integer bottom = number(value, "getBottom", "bottom");
    Integer width = number(value, "getWidth", "width");
    Integer height = number(value, "getHeight", "height");
    if (width == null && x != null && right != null) {
      width = right - x;
    }
    if (height == null && y != null && bottom != null) {
      height = bottom - y;
    }
    if (x == null || y == null || width == null || height == null) {
      return null;
    }
    return new ComputerBounds(x, y, width, height);
  }

  private String text(Object target, String... methods) {
    Object value = callAny(target, methods);
    return value == null || value == FAILED ? null : value.toString();
  }

  private boolean bool(Object target, boolean defaultValue, String... methods) {
    Object value = callAny(target, methods);
    return value instanceof Boolean booleanValue ? booleanValue : defaultValue;
  }

  private Integer number(Object target, String... methods) {
    Object value = callAny(target, methods);
    if (value == null || value == FAILED) {
      value = fieldAny(target, methods);
    }
    return value instanceof Number number ? number.intValue() : null;
  }

  private Object callAny(Object target, String... methods) {
    return callAny(target, new Class<?>[0], new Object[0], methods);
  }

  private Object callAny(Object target, Class<?>[] parameterTypes, Object[] args, String... methods) {
    if (target == null) {
      return null;
    }
    for (String method : methods) {
      try {
        Method candidate = target.getClass().getMethod(method, parameterTypes);
        return candidate.invoke(target, args);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
    }
    return FAILED;
  }

  private Object fieldAny(Object target, String... fields) {
    if (target == null) {
      return null;
    }
    for (String field : fields) {
      try {
        java.lang.reflect.Field candidate = target.getClass().getField(field);
        return candidate.get(target);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
    }
    return null;
  }

  private List<?> asList(Object value) {
    if (value instanceof List<?> list) {
      return list;
    }
    return List.of();
  }

  private Object activeWindow(List<?> windows, ComputerBounds focusedBounds) {
    if (focusedBounds != null) {
      for (Object window : windows) {
        ComputerBounds windowBounds = bounds(window);
        if (contains(windowBounds, focusedBounds)) {
          return window;
        }
      }
    }
    return windows.isEmpty() ? null : windows.getFirst();
  }

  private boolean contains(ComputerBounds outer, ComputerBounds inner) {
    if (outer == null || inner == null || !outer.valid() || !inner.valid()) {
      return false;
    }
    java.awt.Point center = inner.center();
    return center.x >= outer.x() && center.x <= outer.x() + outer.width()
        && center.y >= outer.y() && center.y <= outer.y() + outer.height();
  }

  private Object findCurrentElement(ComputerElement element) {
    List<?> windows = asList(callAny(automation, "getDesktopWindows"));
    for (Object window : windows) {
      Object target = findInWindow(window, element);
      if (target != null) {
        return target;
      }
    }
    return null;
  }

  private Object findInWindow(Object window, ComputerElement element) {
    if (element.automationId() != null && !element.automationId().isBlank()) {
      Object byAutomationId = callAny(window, new Class<?>[] {String.class}, new Object[] {element.automationId()},
          "getControlByAutomationId", "getButtonByAutomationId", "getEditBoxByAutomationId",
          "getCheckBoxByAutomationId");
      if (byAutomationId != FAILED && byAutomationId != null) {
        return byAutomationId;
      }
    }
    if (element.name() != null && !element.name().isBlank()) {
      Object byName = callAny(window, new Class<?>[] {String.class}, new Object[] {element.name()},
          "getControlByName", "getButton", "getEditBox", "getCheckBox", "getTextBox");
      if (byName != FAILED && byName != null) {
        return byName;
      }
    }
    Object children = callAny(window, new Class<?>[] {boolean.class}, new Object[] {false}, "getChildren");
    if (children instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        ComputerElement candidate = toElement(child);
        if (matchesObservedElement(element, candidate)) {
          return child;
        }
      }
    }
    return null;
  }

  private boolean matchesObservedElement(ComputerElement observed, ComputerElement candidate) {
    if (observed.bounds() != null && candidate.bounds() != null && observed.bounds().equals(candidate.bounds())) {
      return true;
    }
    boolean sameRole = observed.role() != null && observed.role().equals(candidate.role());
    boolean sameName = observed.name() != null && !observed.name().isBlank() && observed.name().equals(candidate.name());
    return sameRole && sameName;
  }

  private String normalize(String role) {
    return role == null ? "" : role.toLowerCase(Locale.ROOT);
  }

  private ComputerObservationRequest normalize(ComputerObservationRequest request) {
    if (request == null) {
      return new ComputerObservationRequest(3, 80, true, true);
    }
    return request;
  }

  private Object createAutomation() {
    try {
      Class<?> type = Class.forName("mmarquee.automation.UIAutomation");
      Method getInstance = type.getMethod("getInstance");
      return getInstance.invoke(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static final Object FAILED = new Object();
}
