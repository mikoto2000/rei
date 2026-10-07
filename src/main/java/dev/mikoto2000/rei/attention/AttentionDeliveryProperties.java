package dev.mikoto2000.rei.attention;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The administrator chooses the destination and projects; model text cannot configure delivery. */
@ConfigurationProperties("rei.attention.delivery")
public record AttentionDeliveryProperties(boolean enabled,boolean automatic,String endpoint,String bearerToken,Set<String> projects,String provider) {
  public AttentionDeliveryProperties(boolean enabled,boolean automatic,String endpoint,String bearerToken,Set<String> projects){this(enabled,automatic,endpoint,bearerToken,projects,"WEBHOOK");}
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public AttentionDeliveryProperties {
    provider=provider==null||provider.isBlank()?"WEBHOOK":provider.toUpperCase(java.util.Locale.ROOT);
    if(!Set.of("WEBHOOK","SLACK").contains(provider))throw new IllegalArgumentException("Unknown notification provider");
    projects=projects==null?Set.of():Set.copyOf(projects);
    if(projects.size()>64||projects.stream().anyMatch(p->p.isBlank()||p.length()>128||p.chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("Invalid attention delivery projects");
    if(bearerToken!=null&&(bearerToken.length()>4096||bearerToken.chars().anyMatch(c->c<=32||c>=127)))throw new IllegalArgumentException("Invalid attention delivery credential");
    if(enabled) {
      if(projects.isEmpty())throw new IllegalArgumentException("Attention delivery requires explicit projects");
      if(provider.equals("WEBHOOK"))try {
        if(endpoint==null||endpoint.length()>2048)throw new IllegalArgumentException();
        var uri=URI.create(endpoint);
        boolean https="https".equals(uri.getScheme());
        boolean fixture="http".equals(uri.getScheme())&&Set.of("127.0.0.1","[::1]").contains(uri.getHost());
        if(!(https||fixture)||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()>65535||uri.getPort()==0)throw new IllegalArgumentException();
      }catch(RuntimeException error){throw new IllegalArgumentException("Attention endpoint requires HTTPS without credentials, query or fragment (numeric loopback HTTP is permitted)");}
    }
  }
  @Override public String toString(){return "AttentionDeliveryProperties[enabled="+enabled+", automatic="+automatic+", destination=<configured>, credential=<redacted>, projectCount="+projects.size()+"]";}
}
