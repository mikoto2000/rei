package dev.mikoto2000.rei.computer;

import java.util.Set;

public record ComputerElement(
    String id,
    String role,
    String name,
    String value,
    String automationId,
    String className,
    ComputerBounds bounds,
    boolean enabled,
    boolean focused,
    boolean offscreen,
    Set<ComputerCapability> capabilities) {

  public ComputerElement withId(String id) {
    return new ComputerElement(id, role, name, value, automationId, className, bounds, enabled, focused, offscreen,
        capabilities == null ? Set.of() : Set.copyOf(capabilities));
  }

  public boolean hasCapability(ComputerCapability capability) {
    return capabilities != null && capabilities.contains(capability);
  }
}
