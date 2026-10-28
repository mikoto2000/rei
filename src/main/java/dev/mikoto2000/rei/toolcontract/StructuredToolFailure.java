package dev.mikoto2000.rei.toolcontract;

/** Server-constructed source-free JSON; shared without depending on tools or event delivery. */
public final class StructuredToolFailure extends IllegalArgumentException {
  private final String code;

  public StructuredToolFailure(String code, String json) {
    super(json);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
