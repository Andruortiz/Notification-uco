package co.edu.uco.notification.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  private static final Set<ErrorCode> GENERIC_CODES =
      EnumSet.of(
          ErrorCode.BUSINESS_ERROR, ErrorCode.PROVIDER_ERROR, ErrorCode.INFRASTRUCTURE_ERROR);

  @Test
  void numbersAndTextsAreUnique() {
    final Set<Integer> numbers = new HashSet<>();
    final Set<String> texts = new HashSet<>();
    for (final ErrorCode code : ErrorCode.values()) {
      assertTrue(numbers.add(code.number()), "duplicate number " + code);
      assertTrue(texts.add(code.format()), "duplicate text " + code);
    }
  }

  @Test
  void formatIsThePrefixFollowedByTheNumber() {
    for (final ErrorCode code : ErrorCode.values()) {
      assertEquals("NTF-" + code.number(), code.format());
    }
  }

  @Test
  void everyCodeHasACategoryConsistentWithItsRange() {
    for (final ErrorCode code : ErrorCode.values()) {
      assertNotNull(code.category());
      final int range = code.number() / 1000;
      assertTrue(range >= 1 && range <= 5, "number outside 1xxx-5xxx " + code);
      switch (range) {
        case 1, 2, 3 ->
            assertEquals(FailureCategory.PERMANENT_BUSINESS, code.category(), code.name());
        case 4 ->
            assertTrue(
                code.category() == FailureCategory.RECOVERABLE_PROVIDER
                    || code.category() == FailureCategory.PERMANENT_BUSINESS,
                code.name());
        default ->
            assertEquals(FailureCategory.RECOVERABLE_INFRASTRUCTURE, code.category(), code.name());
      }
    }
  }

  @Test
  void genericCodesAreTheThousandOfTheirRangeAndMatchTheirCategory() {
    assertEquals(3000, ErrorCode.BUSINESS_ERROR.number());
    assertEquals(4000, ErrorCode.PROVIDER_ERROR.number());
    assertEquals(5000, ErrorCode.INFRASTRUCTURE_ERROR.number());
    assertEquals(
        ErrorCode.BUSINESS_ERROR, ErrorCode.genericFor(FailureCategory.PERMANENT_BUSINESS));
    assertEquals(
        ErrorCode.PROVIDER_ERROR, ErrorCode.genericFor(FailureCategory.RECOVERABLE_PROVIDER));
    assertEquals(
        ErrorCode.INFRASTRUCTURE_ERROR,
        ErrorCode.genericFor(FailureCategory.RECOVERABLE_INFRASTRUCTURE));
  }

  @Test
  void everySpecificCodeIsEmittedByProductionCode() throws IOException {
    final String sources = productionSources();
    for (final ErrorCode code : ErrorCode.values()) {
      if (GENERIC_CODES.contains(code)) {
        continue;
      }
      assertTrue(
          sources.contains("ErrorCode." + code.name()), "no production emitter for " + code.name());
    }
  }

  @Test
  void genericCodesAreReachedThroughGenericFor() throws IOException {
    assertTrue(productionSources().contains("ErrorCode.genericFor("));
  }

  private static String productionSources() throws IOException {
    final Path root = Path.of("..").toAbsolutePath().normalize();
    final StringBuilder all = new StringBuilder();
    for (final String module : new String[] {"core", "infrastructure", "utils"}) {
      final Path main = root.resolve(module).resolve("src").resolve("main").resolve("java");
      try (Stream<Path> files = Files.walk(main)) {
        for (final Path file :
            files
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().equals("ErrorCode.java"))
                .collect(Collectors.toList())) {
          all.append(Files.readString(file));
        }
      }
    }
    return all.toString();
  }
}
