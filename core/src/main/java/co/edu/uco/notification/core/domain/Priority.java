package co.edu.uco.notification.core.domain;

public enum Priority {
  LOW(1),
  NORMAL(2),
  HIGH(3);

  private final int weight;

  Priority(final int weight) {
    this.weight = weight;
  }

  public int weight() {
    return weight;
  }
}
