package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.utils.Preconditions;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

  private final AuthenticatedPrincipalArgumentResolver authenticatedPrincipalArgumentResolver;

  public WebFluxConfig(
      final AuthenticatedPrincipalArgumentResolver authenticatedPrincipalArgumentResolver) {
    this.authenticatedPrincipalArgumentResolver =
        Preconditions.requireNonNull(
            authenticatedPrincipalArgumentResolver,
            "authenticatedPrincipalArgumentResolver must not be null");
  }

  @Override
  public void configureArgumentResolvers(final ArgumentResolverConfigurer configurer) {
    configurer.addCustomResolver(authenticatedPrincipalArgumentResolver);
  }
}
