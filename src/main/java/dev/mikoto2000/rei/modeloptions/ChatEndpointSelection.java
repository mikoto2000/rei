package dev.mikoto2000.rei.modeloptions;

import java.util.List;

/** Public connection projections; selecting a registered name never dispatches inference. */
public interface ChatEndpointSelection<P> {
  List<P> endpoints();

  P currentEndpoint();

  void select(String endpoint);
}
