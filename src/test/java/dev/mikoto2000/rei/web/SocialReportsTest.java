package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import dev.mikoto2000.rei.bluesky.*;
import dev.mikoto2000.rei.bluesky.command.BskyCommand;
import dev.mikoto2000.rei.interest.*;
import dev.mikoto2000.rei.interest.command.InterestCommand;
class SocialReportsTest {
  @Test void replyUsesTheExistingPolicyAndShowsTheSamePostReferences() {
    var service=mock(BlueskyPostService.class);
    when(service.reply("at://target","hello world")).thenReturn(new BlueskyPostResult(true,"posted","at://reply","https://bsky.app/reply"));
    assertEquals("posted"+System.lineSeparator()+"postUri: at://reply"+System.lineSeparator()+"postUrl: https://bsky.app/reply",BskyCommand.reportReply(service,"at://target",List.of("hello","world")));
    when(service.reply("at://target")).thenReturn(new BlueskyPostResult(false,"disabled",null,null));
    assertEquals("disabled",BskyCommand.reportReply(service,"at://target",List.of()));
    verify(service).reply("at://target");verify(service).reply("at://target","hello world");
  }
  @Test void emptyHistoriesRetainShellMessagesWithoutPostingOrDiscovery() {
    var history=mock(BlueskyReplyConversationRepository.class);when(history.listHandles()).thenReturn(List.of());when(history.findRecent("alice",20)).thenReturn(List.of());
    assertEquals("会話履歴のあるユーザーはありません",BskyCommand.reportHistoryUsers(history));
    assertEquals("対象ユーザーの会話履歴はありません: alice",BskyCommand.reportHistory(history,"alice",20));
    var interests=mock(InterestUpdateService.class);when(interests.listRecent(24)).thenReturn(List.of());
    assertEquals("興味更新はありません",InterestCommand.reportList(interests,24));
  }
}
