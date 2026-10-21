package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援におけるGrounding通信規約の選択肢。
 *
 * <p>Explicit wire contracts: never guess a coordinate scale from the returned numbers.
 */
enum GroundingProtocol {
  /** ShowUI方式の画面座標指定。 */
  SHOWUI("showui"),
  /** UI-TARS方式の画面座標指定。 */
  UITARS("uitars");

  /** 識別子。 */
  final String id;

  /** 指定された値や利用先を使って、GroundingProtocolを初期化する。 */
  GroundingProtocol(String id) {
    this.id = id;
  }

  /** 画面操作の支援で用いるプロンプトを返す。 */
  String prompt(String target) {
    if (this == UITARS)
      return "Output only the coordinate of one point in your response. What element matches the following task: "
          + target;
    return ShowUiRequestInterceptor.INSTRUCTION + "\n" + target;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  double[] parse(String text) {
    if (this == SHOWUI) return ShowUiComputerVisionModel.parse(text);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text == null || text.length() > 1000) throw invalid();
    var matcher =
        java.util.regex.Pattern.compile(
                "\\s*\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\)\\s*")
            .matcher(text);
    if (!matcher.matches()) throw invalid();
    double x = Double.parseDouble(matcher.group(1)), y = Double.parseDouble(matcher.group(2));
    if (!Double.isFinite(x) || !Double.isFinite(y) || x > 1000 || y > 1000) throw invalid();
    return new double[] {x / 1000, y / 1000};
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static InvalidComputerDecision invalid() {
    return new InvalidComputerDecision(
        "UI-TARS must return exactly one (x,y) point with coordinates in [0,1000]");
  }
}
