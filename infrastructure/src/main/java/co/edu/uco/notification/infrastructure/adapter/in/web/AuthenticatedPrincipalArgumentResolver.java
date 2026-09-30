package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolverSupport;
import org.springframework.web.reactive.result.method.SyncHandlerMethodArgumentResolver;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;
import org.springframework.web.server.ServerWebExchange;

@Component
public class AuthenticatedPrincipalArgumentResolver extends HandlerMethodArgumentResolverSupport
    implements SyncHandlerMethodArgumentResolver, WebFluxConfigurer {

  public AuthenticatedPrincipalArgumentResolver() {
    super(ReactiveAdapterRegistry.getSharedInstance());
  }

  @Override
  public boolean supportsParameter(final MethodParameter parameter) {
    return AuthenticatedPrincipal.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgumentValue(
      final MethodParameter parameter,
      final BindingContext bindingContext,
      final ServerWebExchange exchange) {
    return exchange.getAttribute(AuthenticationWebFilter.PRINCIPAL_ATTRIBUTE);
  }

  @Override
  public void configureArgumentResolvers(final ArgumentResolverConfigurer configurer) {
    configurer.addCustomResolver(this);
  }
}
