package co.edu.uco.notification.core.domain.policy;

public final class TokenMask {

  private static final String STARS = "****";
  private static final int VISIBLE = 4;

  private TokenMask() {}

  public static String mask(final String token) {
    if (token == null || token.length() <= VISIBLE) {
      return STARS;
    }
    return STARS + token.substring(token.length() - VISIBLE);
  }
}
