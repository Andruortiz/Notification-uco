package co.edu.uco.notification.core.domain.valueobject;

public enum Role {
  ADMINISTRADOR,
  OPERADOR,
  CLIENTE;

  public boolean satisfies(final Role required) {
    return this.ordinal() <= required.ordinal();
  }

  public static Role of(final String value) {
    if (value == null) {
      throw new IllegalArgumentException("Role must not be null");
    }
    for (final Role role : values()) {
      if (role.name().equals(value)) {
        return role;
      }
    }
    throw new IllegalArgumentException("Unknown role: " + value);
  }
}
