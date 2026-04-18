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

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "最近の興味更新を一覧します")
  public static class ListCommand implements Runnable {

    private final InterestUpdateService interestUpdateService;

    @Option(names = "--hours", defaultValue = "24", description = "この時間数以内の更新だけ表示します")
    int hours;

    @Override
    public void run() {
      List<InterestUpdate> updates = interestUpdateService.listRecent(hours);
      if (updates.isEmpty()) {
        System.out.println("興味更新はありません");
        return;
      }

      for (InterestUpdate update : updates) {
        String urls = update.sourceUrls().isEmpty() ? "" : String.join(", ", update.sourceUrls());
        System.out.println(update.id() + " | " + update.createdAt() + " | " + update.topic() + " | "
            + update.summary() + " | " + urls);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "discover", description = "興味更新の抽出と検索を手動実行します")
  public static class DiscoverCommand implements Runnable {

    private final InterestDiscoveryJob interestDiscoveryJob;

    @Override
    public void run() {
      List<InterestUpdate> savedUpdates = interestDiscoveryJob.discoverNow(System.out::println);
      System.out.println("興味更新を " + savedUpdates.size() + " 件追加しました");
      for (InterestUpdate update : savedUpdates) {
        System.out.println("- " + update.topic() + " | " + update.summary());
      }
    }
  }
}
