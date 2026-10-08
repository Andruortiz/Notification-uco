package co.edu.uco.notification.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.DashboardQueries;
import co.edu.uco.notification.infrastructure.support.DashboardQueries.Query;
import co.edu.uco.notification.infrastructure.support.PrometheusExpression;
import co.edu.uco.notification.infrastructure.support.PrometheusText;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DashboardQueriesTest {

  private static final String SCRAPE =
      """
      # HELP notification_accepted_total x
      # TYPE notification_accepted_total counter
      notification_accepted_total{channel="EMAIL"} 3.0
      http_server_requests_seconds_bucket{method="POST",status="202",uri="/notifications",le="0.2"} 4
      http_server_requests_seconds_bucket{method="POST",status="202",uri="/notifications",le="+Inf"} 5
      jvm_threads_live_threads 31.0
      """;

  private static final Map<String, Set<String>> EXPOSED = PrometheusText.labelsByMetric(SCRAPE);

  private static String dashboard(final String expression) {
    return "{\"panels\":[{\"title\":\"p\",\"targets\":[{\"expr\":\""
        + expression.replace("\"", "\\\"")
        + "\"}]}]}";
  }

  @Test
  void extractsMetricsAndLabelsFromSelectorsAndGroupings() {
    final PrometheusExpression parsed =
        PrometheusExpression.parse(
            "histogram_quantile(0.95, sum by (le, provider) (rate(notification_provider_duration_seconds_bucket{status=~\"5..\",uri=\"/x\"}[1m]))) * 60");

    assertEquals(Set.of("notification_provider_duration_seconds_bucket"), parsed.metrics());
    assertEquals(Set.of("le", "provider", "status", "uri"), parsed.labels());
  }

  @Test
  void readsEveryTargetExpressionOfADashboardAndOfTheRules() {
    final List<Query> fromDashboard =
        DashboardQueries.fromDashboard(dashboard("sum(rate(notification_accepted_total[1m]))"));
    final List<Query> fromRules =
        DashboardQueries.fromRules(
            "groups:\n  - name: g\n    rules:\n      - alert: A\n        expr: up == 0\n");

    assertEquals(1, fromDashboard.size());
    assertEquals("up == 0", fromRules.get(0).expression());
  }

  @Test
  void rendersGrafanaVariables() {
    assertEquals(
        "rate(x{instance=~\".*\"}[1m])",
        DashboardQueries.render("rate(x{instance=~\"$instance\"}[$__rate_interval])"));
  }

  @Test
  void acceptsQueriesOverExposedMetricsAndLabels() {
    final List<Query> queries =
        DashboardQueries.fromDashboard(
            dashboard(
                "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"notification-service\",uri=\"/notifications\",status=\"202\"}[$__rate_interval])))"));

    assertTrue(DashboardQueries.problems(queries, EXPOSED).isEmpty());
  }

  @Test
  void acceptsTheSynthesizedUpMetric() {
    final List<Query> queries =
        DashboardQueries.fromDashboard(dashboard("up{job=\"notification-service\"}"));

    assertTrue(DashboardQueries.problems(queries, EXPOSED).isEmpty());
  }

  @Test
  void failsOnAMetricThatIsNotExposed() {
    final List<Query> queries =
        DashboardQueries.fromDashboard(dashboard("sum(rate(notification_missing_total[1m]))"));

    final List<String> problems = DashboardQueries.problems(queries, EXPOSED);

    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("notification_missing_total"));
  }

  @Test
  void failsOnALabelThatIsNotExposed() {
    final List<Query> queries =
        DashboardQueries.fromDashboard(
            dashboard("sum by (provider) (rate(notification_accepted_total[1m]))"));

    final List<String> problems = DashboardQueries.problems(queries, EXPOSED);

    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("provider"));
  }

  @Test
  void flagsForbiddenReferencesAndIgnoresCleanContent() {
    assertFalse(
        DashboardQueries.forbiddenReferences("sum by (tenantId) (x) and recipientAddress")
            .isEmpty());
    assertTrue(DashboardQueries.forbiddenReferences("sum by (channel) (x)").isEmpty());
  }
}
