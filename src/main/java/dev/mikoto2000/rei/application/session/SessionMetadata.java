package dev.mikoto2000.rei.application.session;

import java.time.Instant;
import java.util.Objects;
import dev.mikoto2000.rei.core.chat.ResponseStyle;

public record SessionMetadata(String sessionId, String projectId, String title, Instant createdAt, Instant updatedAt, ResponseStyle responseStyle, boolean voiceOnly) {
  public SessionMetadata(String sessionId,String projectId,String title,Instant createdAt,Instant updatedAt) {
    this(sessionId,projectId,title,createdAt,updatedAt,ResponseStyle.NORMAL,false);
  }
  public SessionMetadata {
    if(responseStyle==null)responseStyle=ResponseStyle.NORMAL;
    if(responseStyle==ResponseStyle.NORMAL)voiceOnly=false;
    Objects.requireNonNull(sessionId); Objects.requireNonNull(projectId); Objects.requireNonNull(title);
    Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
  }
  public SessionMetadata withResponseStyle(ResponseStyle style,boolean onlyVoice,Instant time) {
    Objects.requireNonNull(style);
    return new SessionMetadata(sessionId,projectId,title,createdAt,time.isAfter(updatedAt)?time:updatedAt,style,onlyVoice);
  }
  public SessionMetadata touched(Instant time) {
    return new SessionMetadata(sessionId, projectId, title, createdAt, time.isAfter(updatedAt) ? time : updatedAt, responseStyle, voiceOnly);
  }
}
