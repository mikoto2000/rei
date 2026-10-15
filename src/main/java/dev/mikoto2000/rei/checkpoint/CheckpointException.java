package dev.mikoto2000.rei.checkpoint;

import java.util.ArrayList;
import java.util.List;

public class CheckpointException extends IllegalStateException {
  public enum Code {
    DISABLED,
    CAPACITY,
    TASK_BUSY,
    RECONCILIATION_REQUIRED,
    CORRUPT_CHECKPOINT,
    INCOMPATIBLE_SCHEMA
  }

  /** Values describe retained snapshot JSON, not the physical SQLite file size. */
  public record CapacityDetail(
      long revisions,
      long maxRevisions,
      long bytes,
      long incomingBytes,
      long maxBytes,
      List<String> settingNames,
      long protectedRevisions,
      long protectedBytes) {
    public CapacityDetail {
      settingNames = List.copyOf(settingNames);
    }

    /** Negative protection totals mean that this older caller did not collect them. */
    public CapacityDetail(
        long revisions,
        long maxRevisions,
        long bytes,
        long incomingBytes,
        long maxBytes,
        List<String> settingNames) {
      this(revisions, maxRevisions, bytes, incomingBytes, maxBytes, settingNames, -1, -1);
    }
  }

  private final Code code;
  private final CapacityDetail capacityDetail;

  public CheckpointException(Code code, String message) {
    this(code, message, null);
  }

  private CheckpointException(Code code, String message, CapacityDetail detail) {
    super(message);
    this.code = code;
    this.capacityDetail = detail;
  }

  public Code code() {
    return code;
  }

  public CapacityDetail capacityDetail() {
    return capacityDetail;
  }

  public static CheckpointException storageCapacity(
      long revisions, long maxRevisions, long bytes, long incomingBytes, long maxBytes) {
    return storageCapacity(revisions, maxRevisions, bytes, incomingBytes, maxBytes, -1, -1);
  }

  public static CheckpointException storageCapacity(
      long revisions,
      long maxRevisions,
      long bytes,
      long incomingBytes,
      long maxBytes,
      long protectedRevisions,
      long protectedBytes) {
    var settings = new ArrayList<String>();
    if (revisions >= maxRevisions) settings.add("rei.checkpoint.max-revisions");
    if (incomingBytes > maxBytes || bytes > maxBytes - incomingBytes)
      settings.add("rei.checkpoint.max-bytes");
    var detail =
        new CapacityDetail(
            revisions,
            maxRevisions,
            bytes,
            incomingBytes,
            maxBytes,
            settings,
            protectedRevisions,
            protectedBytes);
    String protection =
        protectedRevisions >= 0 && protectedBytes >= 0
            ? "保護されて削除できない履歴: " + protectedRevisions + " 件 / " + protectedBytes + " バイト。"
            : "保護されて削除できない履歴の件数・容量は未取得です。";
    boolean noCandidates = protectedRevisions >= 0 && protectedRevisions == revisions;
    boolean protectedOverLimit =
        protectedRevisions >= maxRevisions
            || protectedBytes >= 0
                && (incomingBytes > maxBytes || protectedBytes > maxBytes - incomingBytes);
    String unavailable =
        noCandidates
            ? "保存済み履歴はすべて保護対象のため、履歴整理では空きを増やせません。"
            : protectedOverLimit ? "保護対象の履歴と次の保存だけで上限を超えるため、履歴整理だけでは保存できません。" : "";
    String message =
        "チェックポイント保存容量の上限に達しました。使用量: "
            + bytes
            + " / "
            + maxBytes
            + " バイト（次の保存に "
            + incomingBytes
            + " バイト必要）、保存件数: "
            + revisions
            + " / "
            + maxRevisions
            + " 件（次の保存に 1 件必要）。上限の設定: "
            + String.join(", ", settings)
            + "。自動整理で削除できる古い履歴を整理しても、保存に必要な空きを確保できませんでした。"
            + protection
            + "各タスクの最新状態と再開・承認続行に必要な履歴を保護します。"
            + "承認待ちでも不要な過去の履歴は整理できます。安全性を確認できない履歴は保守的に保護します。"
            + unavailable
            + "対処: /checkpoint status で使用量を、/checkpoint cleanup で整理対象と保護量を確認してください。"
            + "削除可能な履歴がある場合は /checkpoint cleanup --apply で整理できます。"
            + (noCandidates || protectedOverLimit ? "今回の保存には上限の引き上げが必要です。" : "それでも不足する場合は ")
            + "application.yaml の上記設定値を増やして Rei を再起動し、"
            + "/resume list でタスク ID を確認して /resume <taskId> で再開してください。以前の履歴は保持されています。";
    return new CheckpointException(Code.CAPACITY, message, detail);
  }

  public static CheckpointException snapshotCapacity(long bytes, long maxSnapshotBytes) {
    String setting = "rei.checkpoint.max-snapshot-bytes";
    return new CheckpointException(
        Code.CAPACITY,
        "チェックポイント 1 件の容量上限に達しました。保存対象: "
            + bytes
            + " / "
            + maxSnapshotBytes
            + " バイト。上限の設定: "
            + setting
            + "。対処: application.yaml の "
            + setting
            + " を保存対象より大きい値に増やして Rei を再起動し、"
            + "/resume list でタスク ID を確認して /resume <taskId> で再開してください。"
            + "全体の履歴整理では 1 件の容量は小さくなりません。以前の履歴は保持されています。",
        new CapacityDetail(0, 0, bytes, 0, maxSnapshotBytes, List.of(setting)));
  }
}
