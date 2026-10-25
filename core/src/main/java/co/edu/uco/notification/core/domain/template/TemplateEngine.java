package co.edu.uco.notification.core.domain.template;

import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TemplateEngine {

  private static final Pattern VARIABLE =
      Pattern.compile("\\{\\{\\s{0,5}([A-Za-z][A-Za-z0-9_]{0,39})\\s{0,5}\\}\\}");

  private TemplateEngine() {}

  public static Set<String> detect(final String... texts) {
    final Set<String> names = new LinkedHashSet<>();
    for (final String text : texts) {
      if (text == null) {
        continue;
      }
      final Matcher matcher = VARIABLE.matcher(text);
      int cursor = 0;
      while (matcher.find()) {
        requireNoBraces(text, cursor, matcher.start());
        names.add(matcher.group(1));
        cursor = matcher.end();
      }
      requireNoBraces(text, cursor, text.length());
    }
    return names;
  }

  public static String render(final String text, final Map<String, String> values) {
    final Matcher matcher = VARIABLE.matcher(text);
    final StringBuilder out = new StringBuilder(text.length());
    int cursor = 0;
    while (matcher.find()) {
      out.append(text, cursor, matcher.start());
      final String value = values.get(matcher.group(1));
      if (value != null) {
        out.append(value);
      }
      cursor = matcher.end();
    }
    return out.append(text, cursor, text.length()).toString();
  }

  private static void requireNoBraces(final String text, final int from, final int to) {
    final int open = text.indexOf("{{", from);
    final int close = text.indexOf("}}", from);
    if ((open >= 0 && open < to) || (close >= 0 && close < to)) {
      throw new InvalidTemplateSyntaxException();
    }
  }
}
