package com.stayflow.hotelservice.security;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayflow.common.ErrorDTO;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.*;
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
	private JwtDecoder decoder(String secret, String issuer, String audience, java.time.Clock clock) {
		var decoder = NimbusJwtDecoder
				.withSecretKey(
						new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
				.build();
		var timestamp = new JwtTimestampValidator(java.time.Duration.ofSeconds(30));
		timestamp.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(issuer),
				jwt -> jwt.getAudience().contains(audience)
						? OAuth2TokenValidatorResult.success()
						: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
		return decoder;
	}
	@Bean
	JwtDecoder jwtDecoder(@Value("${JWT_SECRET}") String secret, @Value("${INTERNAL_JWT_SECRET}") String serviceSecret,
			java.time.Clock clock) {
		var user = decoder(secret, "stayflow", "stayflow-api", clock);
		var service = decoder(serviceSecret, "stayflow-service", "hotel-internal", clock);
		return token -> {
			try {
				return user.decode(token);
			} catch (JwtException e) {
				return service.decode(token);
			}
		};
	}
	@Bean
	SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<String> roles = jwt.getClaimAsStringList("roles");
			if (roles == null)
				return List.of();
			return roles.stream()
					.filter(role -> jwt.getClaimAsString("iss").equals("stayflow-service")
							? role.equals("INTERNAL")
							: Set.of("USER", "ADMIN").contains(role))
					.map(role -> new SimpleGrantedAuthority("ROLE_" + role))
					.collect(java.util.stream.Collectors.toList());
		});
		return http.csrf(c -> c.disable()).sessionManagement(
				s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(
						a -> a.requestMatchers("/user/register", "/user/auth", "/actuator/health", "/v3/api-docs/**",
								"/swagger-ui/**", "/swagger-ui.html").permitAll().anyRequest().authenticated())
				.exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> write(json, res, 401))
						.accessDeniedHandler((req, res, ex) -> write(json, res, 403)))
				.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
						.authenticationEntryPoint((req, res, ex) -> write(json, res, 401))
						.accessDeniedHandler((req, res, ex) -> write(json, res, 403)))
				.build();
	}
	private void write(ObjectMapper json, jakarta.servlet.http.HttpServletResponse response, int status)
			throws java.io.IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		json.writeValue(response.getOutputStream(),
				new ErrorDTO(Instant.now(), status, status == 401 ? "UNAUTHORIZED" : "FORBIDDEN",
						status == 401 ? "Authentication required" : "Access denied", MDC.get("traceId")));
	}
}