package dev.mikoto2000.rei.checkpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="rei.checkpoint")
public class CheckpointProperties {
  private boolean enabled=true;
  private int maxRevisions=10000, maxSnapshotBytes=262144, contextCharacters=12000, retentionDays=90;
  private long maxBytes=67108864;
  public boolean isEnabled(){return enabled;}
  public void setEnabled(boolean value){enabled=value;}
  public int getMaxRevisions(){return maxRevisions;}
  public void setMaxRevisions(int value){if(value<1)throw new IllegalArgumentException();maxRevisions=value;}
  public int getMaxSnapshotBytes(){return maxSnapshotBytes;}
  public void setMaxSnapshotBytes(int value){if(value<1024)throw new IllegalArgumentException();maxSnapshotBytes=value;}
  public int getContextCharacters(){return contextCharacters;}
  public void setContextCharacters(int value){if(value<1024)throw new IllegalArgumentException();contextCharacters=value;}
  public int getRetentionDays(){return retentionDays;}
  public void setRetentionDays(int value){if(value<1)throw new IllegalArgumentException();retentionDays=value;}
  public long getMaxBytes(){return maxBytes;}
  public void setMaxBytes(long value){if(value<1024)throw new IllegalArgumentException();maxBytes=value;}
}
