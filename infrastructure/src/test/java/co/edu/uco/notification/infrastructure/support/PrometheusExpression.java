package co.edu.uco.notification.infrastructure.support;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrometheusExpression {

  private static final Set<String> RESERVED =
      Set.of(
          "sum",
          "avg",
          "min",
          "max",
          "count",
          "rate",
          "increase",
          "irate",
          "by",
          "without",
          "on",
          "ignoring",
          "group_left",
          "group_right",
          "and",
          "or",
          "unless",
          "histogram_quantile",
          "avg_over_time",
          "clamp_min",
          "clamp_max",
          "label_values",
          "abs",
          "round",
          "bool",
          "offset");

  private static final Pattern MATCHER =
      Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(=~|!~|!=|=)\\s*\"[^\"]*\"");
  private static final Pattern GROUPING = Pattern.compile("\\b(?:by|without)\\s*\\(([^)]*)\\)");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:]*");

  private final Set<String> metrics = new LinkedHashSet<>();
  private final Set<String> labels = new LinkedHashSet<>();

  private PrometheusExpression(final String expression) {
    final Matcher matchers = MATCHER.matcher(expression);
    while (matchers.find()) {
      labels.add(matchers.group(1));
    }
    String rest = MATCHER.matcher(expression).replaceAll(" ");
    final Matcher groupings = GROUPING.matcher(rest);
    while (groupings.find()) {
      for (final String label : groupings.group(1).split(",")) {
        if (!label.isBlank()) {
          labels.add(label.trim());
        }
      }
    }
    rest = GROUPING.matcher(rest).replaceAll(" ");
    rest = rest.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\"[^\"]*\"", " ");
    final Matcher identifiers = IDENTIFIER.matcher(rest);
    while (identifiers.find()) {
      final String token = identifiers.group();
      if (!RESERVED.contains(token)) {
        metrics.add(token);
      }
    }
  }

  public static PrometheusExpression parse(final String expression) {
    return new PrometheusExpression(expression);
  }

  public Set<String> metrics() {
    return metrics;
  }

  public Set<String> labels() {
    return labels;
  }
}
