package dev.mikoto2000.rei.toolcontract;

/** Server-constructed source-free JSON; shared without depending on tools or event delivery. */
public final class StructuredToolFailure extends IllegalArgumentException {
  @java.io.Serial private static final long serialVersionUID = 8417456516016080907L;

  private final String code;

  public StructuredToolFailure(String code, String json) {
    super(json);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
