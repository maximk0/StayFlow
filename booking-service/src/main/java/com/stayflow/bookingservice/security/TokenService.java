package com.stayflow.bookingservice.security;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.*;
import java.util.List;
@Service
public class TokenService {
	private final JwtEncoder userEncoder;
	private final JwtEncoder serviceEncoder;
	private final Clock clock;
	public TokenService(@Value("${JWT_SECRET}") String userSecret,
			@Value("${INTERNAL_JWT_SECRET}") String serviceSecret, Clock clock) {
		if (userSecret.equals(serviceSecret) || userSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
				|| serviceSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
			throw new IllegalArgumentException("JWT secrets must be distinct and at least 32 bytes");
		this.userEncoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(userSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		this.serviceEncoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(serviceSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		this.clock = clock;
	}
	public String user(Long id, String role) {
		return encode(userEncoder, id.toString(), "stayflow", "stayflow-api", role, 3600);
	}
	public String internal() {
		return encode(serviceEncoder, "booking-service", "stayflow-service", "hotel-internal", "INTERNAL", 60);
	}
	private String encode(JwtEncoder encoder, String subject, String issuer, String audience, String role,
			long seconds) {
		var now = clock.instant();
		var claims = JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(List.of(audience)).issuedAt(now)
				.expiresAt(now.plusSeconds(seconds)).claim("roles", List.of(role)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}
}