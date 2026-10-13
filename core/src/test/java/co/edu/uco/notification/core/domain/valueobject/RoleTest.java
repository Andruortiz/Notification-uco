package co.edu.uco.notification.core.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void administradorSatisfiesEveryRole() {
    assertTrue(Role.ADMINISTRADOR.satisfies(Role.ADMINISTRADOR));
    assertTrue(Role.ADMINISTRADOR.satisfies(Role.OPERADOR));
    assertTrue(Role.ADMINISTRADOR.satisfies(Role.CLIENTE));
  }

  @Test
  void operadorSatisfiesOperadorAndClienteOnly() {
    assertFalse(Role.OPERADOR.satisfies(Role.ADMINISTRADOR));
    assertTrue(Role.OPERADOR.satisfies(Role.OPERADOR));
    assertTrue(Role.OPERADOR.satisfies(Role.CLIENTE));
  }

  @Test
  void clienteSatisfiesClienteOnly() {
    assertFalse(Role.CLIENTE.satisfies(Role.ADMINISTRADOR));
    assertFalse(Role.CLIENTE.satisfies(Role.OPERADOR));
    assertTrue(Role.CLIENTE.satisfies(Role.CLIENTE));
  }

  @Test
  void ofParsesExactName() {
    assertEquals(Role.ADMINISTRADOR, Role.of("ADMINISTRADOR"));
    assertEquals(Role.OPERADOR, Role.of("OPERADOR"));
    assertEquals(Role.CLIENTE, Role.of("CLIENTE"));
  }

  @Test
  void ofRejectsUnknownValue() {
    assertThrows(IllegalArgumentException.class, () -> Role.of("SUPERADMIN"));
  }

  @Test
  void ofRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> Role.of(null));
  }
}
