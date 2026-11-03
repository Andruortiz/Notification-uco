package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "RABBITMQ_USERNAME=test",
      "RABBITMQ_PASSWORD=test"
    })
class ApiContractMatchesControllersTest {

  @Autowired private RequestMappingHandlerMapping handlerMapping;

  private Set<String> served() {
    return handlerMapping.getHandlerMethods().entrySet().stream()
        .filter(
            entry ->
                entry
                    .getValue()
                    .getBeanType()
                    .getPackageName()
                    .startsWith("co.edu.uco.notification"))
        .map(Map.Entry::getKey)
        .flatMap(
            info ->
                info.getMethodsCondition().getMethods().stream()
                    .flatMap(
                        method ->
                            info.getPatternsCondition().getPatterns().stream()
                                .map(pattern -> method.name() + " " + pattern.getPatternString())))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  void theServedOperationsAreExactlyTheAvailableOnesOfTheContract() {
    final Set<String> available =
        ApiContract.operations().stream()
            .filter(ApiContract.Operation::available)
            .map(ApiContract.Operation::key)
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(30, available.size());
    final Set<String> served = served();
    assertTrue(served.containsAll(publicRoutes()));
    served.removeAll(publicRoutes());
    assertEquals(available, served);
  }

  private static Set<String> publicRoutes() {
    return ApiContract.list(ApiContract.document().get("x-public-routes")).stream()
        .map(ApiContract::map)
        .filter(route -> "POST".equals(route.get("method")))
        .map(route -> route.get("method") + " " + route.get("path"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  void everyOperationDeclaresAStatusAndAllowedRoles() {
    ApiContract.operations()
        .forEach(
            operation -> {
              assertTrue(
                  Set.of("available", "planned").contains(operation.status()), operation.key());
              assertFalse(operation.allowedRoles().isEmpty(), operation.key());
              assertEquals(
                  operation.allowedRoles().size(),
                  Set.copyOf(operation.allowedRoles()).size(),
                  operation.key());
              operation
                  .allowedRoles()
                  .forEach(role -> assertTrue(ApiContract.ROLES.contains(role), role));
              assertFalse(operation.definition().containsKey("x-minimum-role"), operation.key());
            });
  }

  @Test
  void noServedPathContainsAColonOrAVariable() {
    served()
        .forEach(
            key -> assertTrue(key.matches("^[A-Z]+ " + ApiContract.PATH_REGEX.substring(1)), key));
  }
}
