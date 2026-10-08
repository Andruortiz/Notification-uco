package co.edu.uco.notification.infrastructure.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public final class DashboardQueries {

  public record Query(String source, String expression) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> TARGET_LABELS = Set.of("job", "instance");
  private static final Set<String> SYNTHESIZED_METRICS = Set.of("up");
  private static final Pattern FORBIDDEN =
      Pattern.compile(
          "tenant|notificationid|correlationid|recipient|destinatario|subject",
          Pattern.CASE_INSENSITIVE);

  private DashboardQueries() {}

  public static Path repositoryFile(final String relative) {
    return Path.of("..").resolve(relative).normalize();
  }

  public static String read(final Path path) {
    try {
      return Files.readString(path);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static List<Query> fromDashboard(final String json) {
    try {
      final List<Query> queries = new ArrayList<>();
      collect(MAPPER.readTree(json), queries);
      return queries;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void collect(final JsonNode node, final List<Query> out) {
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              entry -> {
                if ("targets".equals(entry.getKey()) && entry.getValue().isArray()) {
                  final String title = node.path("title").asText("dashboard");
                  for (final JsonNode target : entry.getValue()) {
                    if (target.hasNonNull("expr")) {
                      out.add(new Query(title, target.get("expr").asText()));
                    }
                  }
                } else {
                  collect(entry.getValue(), out);
                }
              });
    } else if (node.isArray()) {
      node.forEach(child -> collect(child, out));
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Query> fromRules(final String yaml) {
    final List<Query> queries = new ArrayList<>();
    final Map<String, Object> root = new Yaml().load(yaml);
    for (final Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
      for (final Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
        queries.add(new Query(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr"))));
      }
    }
    return queries;
  }

  public static String render(final String expression) {
    return expression
        .replace("$__rate_interval", "1m")
        .replace("$__range", "5m")
        .replace("$instance", ".*");
  }

  public static List<String> problems(
      final List<Query> queries, final Map<String, Set<String>> scrapedLabelsByMetric) {
    final List<String> problems = new ArrayList<>();
    for (final Query query : queries) {
      final PrometheusExpression parsed = PrometheusExpression.parse(render(query.expression()));
      final Set<String> allowed = new HashSet<>(TARGET_LABELS);
      for (final String metric : parsed.metrics()) {
        if (SYNTHESIZED_METRICS.contains(metric)) {
          continue;
        }
        final Set<String> labels = scrapedLabelsByMetric.get(metric);
        if (labels == null) {
          problems.add(query.source() + ": metric not exposed " + metric);
        } else {
          allowed.addAll(labels);
        }
      }
      for (final String label : parsed.labels()) {
        if (!allowed.contains(label)) {
          problems.add(query.source() + ": label not exposed " + label);
        }
      }
    }
    return problems;
  }

  public static List<String> forbiddenReferences(final String content) {
    final List<String> hits = new ArrayList<>();
    final Matcher matcher = FORBIDDEN.matcher(content);
    while (matcher.find()) {
      hits.add(matcher.group());
    }
    return hits;
  }
}
