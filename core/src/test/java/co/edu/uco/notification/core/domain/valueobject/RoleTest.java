package co.edu.uco.notification.core.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void theSixRolesAreDeclaredInTheContractOrder() {
    assertEquals(
        List.of(
            "ADMINISTRADOR_PLATAFORMA",
            "ADMINISTRADOR_APLICACION",
            "EDITOR_PLANTILLAS",
            "OPERADOR",
            "AUDITOR",
            "CLIENTE"),
        Arrays.stream(Role.values()).map(Role::name).toList());
  }

  @Test
  void ofParsesExactName() {
    assertEquals(Role.ADMINISTRADOR_PLATAFORMA, Role.of("ADMINISTRADOR_PLATAFORMA"));
    assertEquals(Role.ADMINISTRADOR_APLICACION, Role.of("ADMINISTRADOR_APLICACION"));
    assertEquals(Role.EDITOR_PLANTILLAS, Role.of("EDITOR_PLANTILLAS"));
    assertEquals(Role.OPERADOR, Role.of("OPERADOR"));
    assertEquals(Role.AUDITOR, Role.of("AUDITOR"));
    assertEquals(Role.CLIENTE, Role.of("CLIENTE"));
  }

  @Test
  void theRetiredAdministradorIsUnknown() {
    assertThrows(IllegalArgumentException.class, () -> Role.of("ADMINISTRADOR"));
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
