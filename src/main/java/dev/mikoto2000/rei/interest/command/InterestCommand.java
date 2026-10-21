package dev.mikoto2000.rei.interest.command;

import dev.mikoto2000.rei.interest.InterestDiscoveryJob;
import dev.mikoto2000.rei.interest.InterestUpdate;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** 興味更新を確認しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "interest",
    description = "興味更新を確認します",
    subcommands = {InterestCommand.ListCommand.class, InterestCommand.DiscoverCommand.class})
public class InterestCommand {
  /** 関心の管理で用いるレポート一覧を返す。 */
  public static String reportList(InterestUpdateService service, int hours) {
    var command = new ListCommand(service);
    command.hours = hours;
    return report(command);
  }

  /** 関心の管理で用いるレポート利用先の検出を返す。 */
  public static String reportDiscovery(InterestDiscoveryJob job) {
    return report(new DiscoverCommand(job));
  }

  /** 関心の管理で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  /** 関心の管理のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** 出力先。 */
    protected java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);
  }

  /** 最近の興味更新を一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "最近の興味更新を一覧します")
  public static class ListCommand extends ReportCommand {

    /** 関心更新サービス。 */
    private final InterestUpdateService interestUpdateService;

    /** 時間数。 */
    @Option(names = "--hours", defaultValue = "24", description = "この時間数以内の更新だけ表示します")
    int hours;

    /** 最近の興味更新を一覧しますを行う。 */
    @Override
    public void run() {
      List<InterestUpdate> updates = interestUpdateService.listRecent(hours);
      if (updates.isEmpty()) {
        out.println("興味更新はありません");
        return;
      }

      for (InterestUpdate update : updates) {
        String urls = update.sourceUrls().isEmpty() ? "" : String.join(", ", update.sourceUrls());
        out.println(
            update.id()
                + " | "
                + update.createdAt()
                + " | "
                + update.topic()
                + " | "
                + update.summary()
                + " | "
                + urls);
      }
    }
  }

  /** 興味更新の抽出と検索を手動実行しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "discover", description = "興味更新の抽出と検索を手動実行します")
  public static class DiscoverCommand extends ReportCommand {

    /** 関心利用先の検出実行する作業。 */
    private final InterestDiscoveryJob interestDiscoveryJob;

    /** 興味更新の抽出と検索を手動実行しますを行う。 */
    @Override
    public void run() {
      List<InterestUpdate> savedUpdates = interestDiscoveryJob.discoverNow(out::println);
      out.println("興味更新を " + savedUpdates.size() + " 件追加しました");
      for (InterestUpdate update : savedUpdates) {
        out.println("- " + update.topic() + " | " + update.summary());
      }
    }
  }
}
