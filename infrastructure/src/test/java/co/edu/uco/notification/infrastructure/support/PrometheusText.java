package co.edu.uco.notification.infrastructure.support;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrometheusText {

  private static final Pattern LABEL = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=\"");

  private PrometheusText() {}

  public static Map<String, Set<String>> labelsByMetric(final String text) {
    final Map<String, Set<String>> result = new HashMap<>();
    for (final String line : text.split("\n")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final int brace = line.indexOf('{');
      final int space = line.indexOf(' ');
      final boolean labelled = brace >= 0 && brace < space;
      final int end = labelled ? brace : space;
      final Set<String> labels =
          result.computeIfAbsent(line.substring(0, end), key -> new HashSet<>());
      if (labelled) {
        final Matcher matcher = LABEL.matcher(line.substring(brace, line.lastIndexOf('}') + 1));
        while (matcher.find()) {
          labels.add(matcher.group(1));
        }
      }
    }
    return result;
  }
}
