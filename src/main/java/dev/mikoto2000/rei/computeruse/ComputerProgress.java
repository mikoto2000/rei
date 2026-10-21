package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援で受け渡す画面操作進捗を表す。
 *
 * <p>No screenshot or typed text is carried through progress events.
 *
 * @param step 手順
 * @param phase 段階
 * @param action 操作
 * @param target 対象
 * @param x 横方向の位置
 * @param y 縦方向の位置
 * @param confidence 確信度
 * @param reason 理由
 */
public record ComputerProgress(
    int step,
    String phase,
    String action,
    String target,
    Integer x,
    Integer y,
    Double confidence,
    String reason) {}
