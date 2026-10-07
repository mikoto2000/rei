package dev.mikoto2000.rei.vectorstore;
import java.util.*;

/** Optional learned sparse provider boundary. Numeric vocabulary IDs never imply BM25 term weights. */
public interface SparseEncoder {
  String modelId();
  Vector encode(String text,Runnable checkActive);
  record Vector(String modelId,int dimensions,Map<Integer,Double> weights){
    public Vector{if(modelId==null||!modelId.matches("[A-Za-z0-9._:/-]{1,128}")||dimensions<1||dimensions>1048576||weights==null||weights.size()>4096)throw new IllegalArgumentException("Bounded versioned sparse vector required");
      for(var entry:weights.entrySet())if(entry.getKey()==null||entry.getKey()<0||entry.getKey()>=dimensions||entry.getValue()==null||!Double.isFinite(entry.getValue())||entry.getValue()<=0||entry.getValue()>1000000)throw new IllegalArgumentException("Invalid sparse vocabulary ID or weight");weights=Collections.unmodifiableMap(new TreeMap<>(weights));}
  }
}
