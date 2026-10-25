package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.DashboardQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class LoadTestDashboardCoherenceTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String DASHBOARD =
      "observability/grafana/dashboards/notification-service.json";
  private static final String VERDICT = "loadtest/veredicto.js";
  private static final String LATENCY_PANEL = "Ultima prueba de carga: p95 de aceptacion (s)";
  private static final String DISPATCH_PANEL = "Ultima prueba de carga: despachadas por minuto";

  private static String repositoryText(final String relative) {
    return DashboardQueries.read(DashboardQueries.repositoryFile(relative));
  }

  private static String capture(final String source, final String regex) {
    final Matcher matcher = Pattern.compile(regex).matcher(source);
    assertTrue(matcher.find(), "veredicto.js must define " + regex);
    return matcher.group(1);
  }

  private static JsonNode panel(final String dashboard, final String title) throws Exception {
    for (final JsonNode panel : MAPPER.readTree(dashboard).get("panels")) {
      if (title.equals(panel.path("title").asText())) {
        return panel;
      }
    }
    return null;
  }

  private static List<String> problems(final String dashboard, final String verdict)
      throws Exception {
    final List<String> problems = new ArrayList<>();
    final String latencyMetric = "k6_" + capture(verdict, "aceptacion:\\s*'(\\w+)'") + "_p95";
    final String dispatchMetric = "k6_" + capture(verdict, "despacho:\\s*'(\\w+)'");
    final double latencyLimit = Double.parseDouble(capture(verdict, "p95Seconds:\\s*([\\d.]+)"));
    final double dispatchLimit =
        Double.parseDouble(capture(verdict, "despachoPorMinuto:\\s*([\\d.]+)"));
    check(problems, dashboard, LATENCY_PANEL, latencyMetric, latencyLimit);
    check(problems, dashboard, DISPATCH_PANEL, dispatchMetric, dispatchLimit);
    return problems;
  }

  private static void check(
      final List<String> problems,
      final String dashboard,
      final String title,
      final String metric,
      final double limit)
      throws Exception {
    final JsonNode panel = panel(dashboard, title);
    if (panel == null) {
      problems.add("missing panel " + title);
      return;
    }
    final String expression = panel.at("/targets/0/expr").asText();
    if (!expression.equals(metric)) {
      problems.add(title + ": expected " + metric + " but found " + expression);
    }
    final double threshold = panel.at("/fieldConfig/defaults/thresholds/steps/1/value").asDouble();
    if (threshold != limit) {
      problems.add(title + ": threshold " + threshold + " differs from " + limit);
    }
  }

  @Test
  void theDashboardPanelsUseTheMetricNamesAndThresholdsOfTheVerdictModule() throws Exception {
    assertEquals(List.of(), problems(repositoryText(DASHBOARD), repositoryText(VERDICT)));
  }

  @Test
  void aMissingMetricReferenceIsDetected() throws Exception {
    final String tampered =
        repositoryText(DASHBOARD)
            .replace("k6_notificaciones_despachadas_por_minuto", "k6_inexistente");

    final List<String> problems = problems(tampered, repositoryText(VERDICT));

    assertFalse(problems.isEmpty());
    assertTrue(problems.stream().anyMatch(problem -> problem.contains("k6_inexistente")));
  }

  @Test
  void aDifferentThresholdIsDetected() throws Exception {
    final String tampered =
        repositoryText(VERDICT).replace("despachoPorMinuto: 500", "despachoPorMinuto: 400");

    assertFalse(problems(repositoryText(DASHBOARD), tampered).isEmpty());
  }

  @Test
  void thePrometheusOfTheDashboardAcceptsRemoteWrite() {
    assertTrue(
        repositoryText("docker-compose.observability.yml")
            .contains("--web.enable-remote-write-receiver"));
  }

  @Test
  void theLoadScriptsPublishTheTrendPercentileTheDashboardReads() {
    assertTrue(repositoryText("loadtest/run.sh").contains("K6_PROMETHEUS_RW_TREND_STATS=\"p(95)"));
    assertTrue(
        repositoryText("loadtest/run.ps1").contains("K6_PROMETHEUS_RW_TREND_STATS = 'p(95)"));
  }

  @Test
  void thePullRequestWorkflowDoesNotNeedK6() {
    assertFalse(repositoryText(".github/workflows/ci-cd.yml").toLowerCase().contains("k6"));
    assertTrue(
        repositoryText(".github/workflows/carga-nocturna.yml").contains("workflow_dispatch"));
  }
}
