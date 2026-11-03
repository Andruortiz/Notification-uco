package co.edu.uco.notification.infrastructure.adapter.out.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeviceConfigTest {

  private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  @Test
  void ephemeralKeysAreAllowedOnlyWhenEveryActiveProfileIsLocalOrTest() {
    assertTrue(DeviceConfig.ephemeralKeysAllowed(new String[] {"local"}));
    assertTrue(DeviceConfig.ephemeralKeysAllowed(new String[] {"local", "test"}));
    assertFalse(DeviceConfig.ephemeralKeysAllowed(new String[] {"local", "prod"}));
    assertFalse(DeviceConfig.ephemeralKeysAllowed(new String[] {"prod"}));
    assertFalse(DeviceConfig.ephemeralKeysAllowed(new String[] {}));
  }

  @Test
  void missingKeysFailTheStartupWhenAnyProfileIsNotLocalOrTest() {
    assertThrows(
        IllegalStateException.class,
        () -> DeviceConfig.protection(new String[] {"local", "prod"}, "", 1, "", 1));
    assertThrows(
        IllegalStateException.class, () -> DeviceConfig.protection(new String[] {}, KEY, 1, "", 1));
  }

  @Test
  void localStartsWithEphemeralKeysAndConfiguredKeysAlwaysWin() {
    assertNotNull(DeviceConfig.protection(new String[] {"local"}, "", 1, "", 1));
    assertEquals(3, DeviceConfig.protection(new String[] {"prod"}, KEY, 3, KEY, 1).keyVersion());
  }
}
