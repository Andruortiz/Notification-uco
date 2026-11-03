package co.edu.uco.notification.core.domain.template;

public enum TemplateCategory {
  ACADEMICO(false),
  ADMINISTRATIVO(false),
  MARKETING(false),
  SEGURIDAD(true);

  private final boolean critical;

  TemplateCategory(final boolean critical) {
    this.critical = critical;
  }

  public boolean critical() {
    return critical;
  }
}
