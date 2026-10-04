package dev.mikoto2000.rei.core.policy;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.tool-permission")
public record ToolPermissionProperties(boolean enabled, Set<ActionCapability> autoApprove,
    Set<ActionCapability> denied, Map<String,Set<ActionCapability>> capabilities) {
  public ToolPermissionProperties {
    autoApprove=autoApprove==null?Set.of(ActionCapability.READ):Set.copyOf(autoApprove);
    denied=denied==null?Set.of(ActionCapability.DESTRUCTIVE):Set.copyOf(denied);
    var copy=new HashMap<String,Set<ActionCapability>>();
    if(capabilities!=null) capabilities.forEach((name,values)->{
      if(name==null || name.isBlank() || values==null || values.isEmpty())
        throw new IllegalArgumentException("Tool capability classification must not be empty");
      copy.put(name,Set.copyOf(values));
    });
    capabilities=Map.copyOf(copy);
  }
}
