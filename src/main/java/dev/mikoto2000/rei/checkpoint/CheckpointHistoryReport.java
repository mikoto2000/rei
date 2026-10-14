package dev.mikoto2000.rei.checkpoint;

/** Shared terminal text; no execution or retention decisions are made by this renderer. */
public final class CheckpointHistoryReport {
  private CheckpointHistoryReport(){}

  public static String usage(CheckpointHistory.Usage usage){
    return "チェックポイント保存量（全プロジェクト）\n"
        +"保存容量: "+usage.snapshotBytes()+" / "+usage.maxBytes()+" バイト（rei.checkpoint.max-bytes）\n"
        +"保存件数: "+usage.revisions()+" / "+usage.maxRevisions()+" 件（rei.checkpoint.max-revisions）\n"
        +"重複実行防止キー: "+usage.eventKeys()+" 件（整理後も保持・保存件数の上限対象外）\n"
        +"整理候補の確認: /checkpoint cleanup";
  }

  public static String cleanup(CheckpointHistory.Cleanup cleanup){
    var result=new StringBuilder(cleanup.applied()?"チェックポイント履歴を整理しました（全プロジェクト）。":"チェックポイント履歴の整理候補（全プロジェクト・まだ削除していません）。");
    result.append('\n').append(cleanup.applied()?"削除した履歴: ":"削除可能な履歴: ")
        .append(cleanup.removedRevisions()).append(" 件 / ").append(cleanup.removedBytes()).append(" バイト\n")
        .append("保護対象の履歴を持つタスク: ").append(cleanup.protectedTasks()).append(" 件\n");
    if(cleanup.protectedRevisions()>=0&&cleanup.protectedBytes()>=0){
      result.append("保護されて削除できない履歴: ").append(cleanup.protectedRevisions())
          .append(" 件 / ").append(cleanup.protectedBytes()).append(" バイト\n");
    }else{
      result.append("保護されて削除できない履歴の件数・容量: 未取得\n");
    }
    result.append("各タスクの最新状態と再開・承認続行に必要な履歴、重複実行防止キーを保持します。\n")
        .append("実行中・承認待ちのタスクでも不要な過去の履歴は古い順に整理できます。安全性を確認できない履歴は保守的に保護します。\n")
        .append(usage(cleanup.applied()?cleanup.after():cleanup.before()));
    if(cleanup.removedRevisions()==0){
      result.append("\n削除可能な履歴はありません。保護対象の履歴は /checkpoint cleanup --apply でも削除されません。");
    }
    if(cleanup.applied()){
      result.append("\n削除した過去の revision は復元できません。DBファイルのサイズはそのままでも、チェックポイント保存枠は再利用できます。");
    }else{
      result.append("\n整理を実行するには: /checkpoint cleanup --apply\n削除した過去の revision は復元できません。");
    }
    return result.toString();
  }
}
