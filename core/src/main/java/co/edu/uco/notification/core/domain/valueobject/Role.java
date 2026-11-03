package co.edu.uco.notification.core.domain.valueobject;

public enum Role {
  ADMINISTRADOR_PLATAFORMA,
  ADMINISTRADOR_APLICACION,
  EDITOR_PLANTILLAS,
  OPERADOR,
  AUDITOR,
  CLIENTE;

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
