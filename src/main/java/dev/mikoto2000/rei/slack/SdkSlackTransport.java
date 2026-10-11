package dev.mikoto2000.rei.slack;
import java.util.*;
import com.slack.api.methods.*;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;

final class SdkSlackTransport implements SlackTransport {
  private final MethodsClient methods;private final String team;
  SdkSlackTransport(MethodsClient methods,String team){this.methods=methods;this.team=team;}
  @Override public Delivery send(SlackStore.Outgoing row) {
    if(!team.equals(row.team()))return Delivery.failed();
    try {
      var result=methods.chatPostMessage(ChatPostMessageRequest.builder().channel(row.channel()).threadTs(row.thread())
          .text(row.text()).mrkdwn(true).parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).replyBroadcast(false).build());
      if(result.isOk())return row.channel().equals(result.getChannel())&&result.getTs()!=null?Delivery.sent(result.getTs()):Delivery.unknown();
      String error=result.getError();
      if("ratelimited".equals(error)||"rate_limited".equals(error)) {
        var headers=result.getHttpResponseHeaders();int delay=60;
        if(headers!=null)for(var entry:headers.entrySet())if(entry.getKey().equalsIgnoreCase("Retry-After")&&!entry.getValue().isEmpty())delay=seconds(entry.getValue().getFirst());
        return Delivery.retry(delay);
      }
      return error!=null&&Set.of("invalid_auth","not_authed","token_revoked","token_expired","account_inactive","channel_not_found","not_in_channel","missing_scope","no_permission","is_archived","invalid_arguments","no_text").contains(error)?Delivery.failed():Delivery.unknown();
    }catch(SlackApiException error) {
      if(error.getResponse()!=null&&error.getResponse().code()==429)return Delivery.retry(seconds(error.getResponse().header("Retry-After")));
      return Delivery.unknown();
    }catch(Exception error){return Delivery.unknown();}
  }
  private static int seconds(String raw){try{return Math.max(1,Integer.parseInt(raw));}catch(Exception ignored){return 60;}}
}
