package dev.mikoto2000.rei.computeruse;

/** Explicit wire contracts: never guess a coordinate scale from the returned numbers. */
enum GroundingProtocol {
  SHOWUI("showui"), UITARS("uitars");

  final String id;
  GroundingProtocol(String id) { this.id = id; }

  String prompt(String target) {
    if (this == UITARS)
      return "Output only the coordinate of one point in your response. What element matches the following task: " + target;
    return ShowUiRequestInterceptor.INSTRUCTION + "\n" + target;
  }

  double[] parse(String text) {
    if (this == SHOWUI) return ShowUiComputerVisionModel.parse(text);
    if (text == null || text.length() > 1000) throw invalid();
    var matcher = java.util.regex.Pattern.compile("\\s*\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\)\\s*").matcher(text);
    if (!matcher.matches()) throw invalid();
    double x = Double.parseDouble(matcher.group(1)), y = Double.parseDouble(matcher.group(2));
    if (!Double.isFinite(x) || !Double.isFinite(y) || x > 1000 || y > 1000) throw invalid();
    return new double[]{x / 1000, y / 1000};
  }

  private static InvalidComputerDecision invalid() {
    return new InvalidComputerDecision("UI-TARS must return exactly one (x,y) point with coordinates in [0,1000]");
  }
}
