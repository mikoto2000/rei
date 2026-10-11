package dev.mikoto2000.rei.interest.command;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.interest.InterestUpdate;
import dev.mikoto2000.rei.interest.InterestDiscoveryJob;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = "interest",
    description = "興味更新を確認します",
    subcommands = {
      InterestCommand.ListCommand.class,
      InterestCommand.DiscoverCommand.class
    })
public class InterestCommand {
  public static String reportList(InterestUpdateService service,int hours){var command=new ListCommand(service);command.hours=hours;return report(command);}
  public static String reportDiscovery(InterestDiscoveryJob job){return report(new DiscoverCommand(job));}
  private static String report(ReportCommand command){var text=new java.io.StringWriter();command.out=new java.io.PrintWriter(text,true);command.run();return text.toString().replaceAll("[\\r\\n]+$","");}
  private abstract static class ReportCommand implements Runnable { protected java.io.PrintWriter out=new java.io.PrintWriter(System.out,true); }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "最近の興味更新を一覧します")
  public static class ListCommand extends ReportCommand {

    private final InterestUpdateService interestUpdateService;

    @Option(names = "--hours", defaultValue = "24", description = "この時間数以内の更新だけ表示します")
    int hours;

    @Override
    public void run() {
      List<InterestUpdate> updates = interestUpdateService.listRecent(hours);
      if (updates.isEmpty()) {
        out.println("興味更新はありません");
        return;
      }

      for (InterestUpdate update : updates) {
        String urls = update.sourceUrls().isEmpty() ? "" : String.join(", ", update.sourceUrls());
        out.println(update.id() + " | " + update.createdAt() + " | " + update.topic() + " | "
            + update.summary() + " | " + urls);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "discover", description = "興味更新の抽出と検索を手動実行します")
  public static class DiscoverCommand extends ReportCommand {

    private final InterestDiscoveryJob interestDiscoveryJob;

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
