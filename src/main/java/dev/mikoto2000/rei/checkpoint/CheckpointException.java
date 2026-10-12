package dev.mikoto2000.rei.checkpoint;

import java.util.ArrayList;
import java.util.List;

public class CheckpointException extends IllegalStateException {
  public enum Code { DISABLED, CAPACITY, TASK_BUSY, RECONCILIATION_REQUIRED, CORRUPT_CHECKPOINT, INCOMPATIBLE_SCHEMA }
  /** Values describe retained snapshot JSON, not the physical SQLite file size. */
  public record CapacityDetail(long revisions, long maxRevisions, long bytes, long incomingBytes,
      long maxBytes, List<String> settingNames) {
    public CapacityDetail { settingNames=List.copyOf(settingNames); }
  }
  private final Code code;
  private final CapacityDetail capacityDetail;
  public CheckpointException(Code code,String message){this(code,message,null);}
  private CheckpointException(Code code,String message,CapacityDetail detail){super(message);this.code=code;this.capacityDetail=detail;}
  public Code code(){return code;}
  public CapacityDetail capacityDetail(){return capacityDetail;}

  public static CheckpointException storageCapacity(long revisions,long maxRevisions,long bytes,
      long incomingBytes,long maxBytes) {
    var settings=new ArrayList<String>();
    if(revisions>=maxRevisions)settings.add("rei.checkpoint.max-revisions");
    if(incomingBytes>maxBytes || bytes>maxBytes-incomingBytes)settings.add("rei.checkpoint.max-bytes");
    var detail=new CapacityDetail(revisions,maxRevisions,bytes,incomingBytes,maxBytes,settings);
    String message="チェックポイント保存容量の上限に達しました。使用量: " + bytes + " / " + maxBytes
        + " バイト（次の保存に " + incomingBytes + " バイト必要）、保存件数: " + revisions + " / " + maxRevisions
        + " 件（次の保存に 1 件必要）。上限の設定: " + String.join(", ",settings)
        + "。自動整理で削除できる古い履歴を整理しても、保存に必要な空きを確保できませんでした。"
        + "対処: /checkpoint status で使用量を確認し、/checkpoint cleanup で整理対象を確認、"
        + "/checkpoint cleanup --apply で整理してください。最新の再開用履歴・実行中・承認待ち・破損した履歴は保護されます。"
        + "それでも不足する場合は application.yaml の上記設定値を増やして Rei を再起動し、"
        + "/resume list でタスク ID を確認して /resume <taskId> で再開してください。以前の履歴は保持されています。";
    return new CheckpointException(Code.CAPACITY,message,detail);
  }

  public static CheckpointException snapshotCapacity(long bytes,long maxSnapshotBytes) {
    String setting="rei.checkpoint.max-snapshot-bytes";
    return new CheckpointException(Code.CAPACITY,
        "チェックポイント 1 件の容量上限に達しました。保存対象: " + bytes + " / " + maxSnapshotBytes
            + " バイト。上限の設定: " + setting
            + "。対処: application.yaml の " + setting + " を保存対象より大きい値に増やして Rei を再起動し、"
            + "/resume list でタスク ID を確認して /resume <taskId> で再開してください。"
            + "全体の履歴整理では 1 件の容量は小さくなりません。以前の履歴は保持されています。",
        new CapacityDetail(0,0,bytes,0,maxSnapshotBytes,List.of(setting)));
  }
}
