package dev.mikoto2000.rei.artifact;
import java.time.Duration;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.artifacts")
public class ArtifactProperties {
  private boolean enabled;
  private long maxBytes=32L*1024*1024,maxTotalBytes=512L*1024*1024;
  private int maxArtifacts=1024;
  private Duration retention=Duration.ofDays(30);
  public boolean isEnabled(){return enabled;}public void setEnabled(boolean value){enabled=value;}
  public long getMaxBytes(){return maxBytes;}public void setMaxBytes(long value){maxBytes=value;}
  public long getMaxTotalBytes(){return maxTotalBytes;}public void setMaxTotalBytes(long value){maxTotalBytes=value;}
  public int getMaxArtifacts(){return maxArtifacts;}public void setMaxArtifacts(int value){maxArtifacts=value;}
  public Duration getRetention(){return retention;}public void setRetention(Duration value){retention=value;}
  public void validate(){
    if(maxBytes<1||maxBytes>32L*1024*1024||maxTotalBytes<maxBytes||maxTotalBytes>1024L*1024*1024
        ||maxArtifacts<1||maxArtifacts>10000||retention==null||retention.compareTo(Duration.ofMinutes(1))<0||retention.compareTo(Duration.ofDays(90))>0)
      throw new IllegalArgumentException("Invalid Artifact limits");
  }
}
