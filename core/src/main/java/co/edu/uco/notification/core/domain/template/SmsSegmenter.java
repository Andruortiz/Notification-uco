package co.edu.uco.notification.core.domain.template;

import java.util.Arrays;

public final class SmsSegmenter {

  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  private static final String EXTENSION = "^{}\\[~]|€\f";

  private SmsSegmenter() {}

  public static SmsSegmentation segment(final String text) {
    final int[] points = text.codePoints().toArray();
    final boolean gsm = Arrays.stream(points).allMatch(SmsSegmenter::inGsm);
    final int single = gsm ? 160 : 70;
    final int capacity = gsm ? 153 : 67;
    final int total = Arrays.stream(points).map(point -> weight(point, gsm)).sum();
    final int segments = segments(points, gsm, total, single, capacity);
    return new SmsSegmentation(
        gsm ? SmsEncoding.GSM7 : SmsEncoding.UCS2,
        total,
        segments > 1 ? capacity : single,
        segments,
        segments > 1);
  }

  private static int segments(
      final int[] points,
      final boolean gsm,
      final int total,
      final int single,
      final int capacity) {
    if (total == 0) {
      return 0;
    }
    if (total <= single) {
      return 1;
    }
    int segments = 1;
    int current = 0;
    for (final int point : points) {
      final int width = weight(point, gsm);
      if (current + width > capacity) {
        segments++;
        current = width;
      } else {
        current += width;
      }
    }
    return segments;
  }

  private static boolean inGsm(final int point) {
    return BASIC.indexOf(point) >= 0 || EXTENSION.indexOf(point) >= 0;
  }

  private static int weight(final int point, final boolean gsm) {
    if (!gsm) {
      return Character.charCount(point);
    }
    return EXTENSION.indexOf(point) >= 0 ? 2 : 1;
  }
}
