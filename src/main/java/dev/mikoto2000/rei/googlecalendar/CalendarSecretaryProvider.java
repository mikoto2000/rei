package dev.mikoto2000.rei.googlecalendar;

import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Secretary adapter keeps the legacy Calendar service and its callers on their original API. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class CalendarSecretaryProvider implements SecretaryProvider {
  private final ObjectProvider<GoogleCalendarService> services;

  public CalendarSecretaryProvider(ObjectProvider<GoogleCalendarService> services) {
    this.services = services;
  }

  private GoogleCalendarService service() {
    return services.getObject();
  }

  @Override
  public Set<Service> services() {
    return Set.of(Service.CALENDAR);
  }

  @Override
  public Auth status(Service service) {
    return service().status(service);
  }

  @Override
  public String identity(SecretaryConnections.Connection c) {
    return service().identity(c);
  }

  @Override
  public Page poll(SecretaryConnections.Connection c, String cursor) {
    return service().poll(c, cursor);
  }

  @Override
  public Page read(SecretaryConnections.Connection c, String operation, Map<String, String> args) {
    return service().read(c, operation, args);
  }

  @Override
  public Outcome write(
      SecretaryConnections.Connection c, String operation, Map<String, String> args, String key) {
    return service().performSecretaryAction(c, operation, args, key);
  }

  @Override
  public Outcome verify(
      SecretaryConnections.Connection c,
      String operation,
      Map<String, String> args,
      String key,
      Outcome previous) {
    return service().verify(c, operation, args, key, previous);
  }

  @Override
  public Auth authorizeConnection(Service service, boolean force) {
    return service().authorizeConnection(service, force);
  }
}
