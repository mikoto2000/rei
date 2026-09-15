package dev.mikoto2000.rei.core.project;

import java.util.*;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

/** Existing state services keep their API; each project gets a distinct instance. */
public final class ProjectBeanScope implements Scope {
  private final java.util.function.Supplier<String> identity;
  public ProjectBeanScope() {
    this(() -> { var context = ProjectService.contextForOperation(); return context == null ? "global" : context.id(); });
  }
  public ProjectBeanScope(java.util.function.Supplier<String> identity) { this.identity = identity; }
  private final Map<String, Map<String, Object>> projects = new HashMap<>();
  private final Map<String, Runnable> destruction = new HashMap<>();
  public synchronized Object get(String name, ObjectFactory<?> factory) {
    return projects.computeIfAbsent(getConversationId(), key -> new HashMap<>()).computeIfAbsent(name, key -> factory.getObject());
  }
  public synchronized Object remove(String name) {
    var beans = projects.get(getConversationId()); return beans == null ? null : beans.remove(name);
  }
  public synchronized void registerDestructionCallback(String name, Runnable callback) {
    destruction.put(getConversationId() + ":" + name, callback);
  }
  public Object resolveContextualObject(String key) { return null; }
  public String getConversationId() {
    return identity.get();
  }
  public synchronized void close() { destruction.values().forEach(Runnable::run); destruction.clear(); projects.clear(); }
}
