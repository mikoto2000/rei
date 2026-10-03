package dev.mikoto2000.rei.web;
public record MemoryResponse(String id,String content,String type,String scope,String status,double confidence,String expiresAt,String createdAt,String updatedAt) {
  public static MemoryResponse from(dev.mikoto2000.rei.memory.model.Memory m) { return new MemoryResponse(m.id(),m.content(),m.type().name(),m.scope().name(),m.status().name(),m.confidence(),m.expiresAt()==null?null:m.expiresAt().toString(),m.createdAt().toString(),m.updatedAt().toString()); }
}
