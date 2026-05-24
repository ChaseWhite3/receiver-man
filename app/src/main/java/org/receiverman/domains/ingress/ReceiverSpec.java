package org.receiverman.domains.ingress;

public record ReceiverSpec(String id, String parser) {
  public ReceiverSpec {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    id = id.trim();
    parser = parser == null || parser.isBlank() ? "default" : parser.trim();
  }
}
