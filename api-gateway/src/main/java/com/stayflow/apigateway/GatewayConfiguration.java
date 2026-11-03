package com.stayflow.apigateway;
import org.springframework.context.annotation.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.jwt.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
@Configuration
public class GatewayConfiguration {
	@Bean
	RouteLocator routes(RouteLocatorBuilder builder) {
		return builder.routes()
				.route("booking",
						r -> r.path("/user", "/user/**", "/booking", "/booking/**", "/bookings", "/bookings/**",
								"/api/bookings", "/api/bookings/**").uri("lb://booking-service"))
				.route("hotel", r -> r.path("/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
						.uri("lb://hotel-service"))
				.build();
	}
	@Bean
	ReactiveJwtDecoder decoder(@Value("${JWT_SECRET}") String secret) {
		var decoder = NimbusReactiveJwtDecoder
				.withSecretKey(
						new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
				.build();
		decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer("stayflow"),
				jwt -> jwt.getAudience().contains("stayflow-api")
						? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
						: org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
								.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"))));
		return decoder;
	}
	@Bean
	SecurityWebFilterChain security(ServerHttpSecurity http, GatewayErrors errors) {
		return http.csrf(c -> c.disable())
				.authorizeExchange(a -> a.pathMatchers("/api/rooms/*/confirm-availability", "/api/rooms/*/release")
						.denyAll().pathMatchers("/user/register", "/user/auth", "/actuator/health").permitAll()
						.anyExchange().authenticated())
				.exceptionHandling(e -> e.authenticationEntryPoint((exchange, error) -> errors.write(exchange, 401))
						.accessDeniedHandler((exchange, error) -> errors.write(exchange, 403)))
				.oauth2ResourceServer(o -> o.jwt(j -> {
				}).authenticationEntryPoint((exchange, error) -> errors.write(exchange, 401))
						.accessDeniedHandler((exchange, error) -> errors.write(exchange, 403)))
				.build();
	}
}
