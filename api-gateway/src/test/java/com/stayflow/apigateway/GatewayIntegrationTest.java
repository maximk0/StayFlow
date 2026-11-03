package com.stayflow.apigateway;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import java.time.Instant;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"JWT_SECRET=test-only-signing-material-at-least-32-bytes", "eureka.client.enabled=false",
		"spring.cloud.loadbalancer.cache.enabled=false"})
@AutoConfigureWebTestClient
class GatewayIntegrationTest {
	static HttpServer backend;
	static {
		try {
			backend = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			backend.createContext("/", e -> {
				String result = "{\"path\":\"" + e.getRequestURI().getPath() + "\",\"trace\":\""
						+ e.getRequestHeaders().getFirst("X-Trace-Id") + "\",\"auth\":"
						+ (e.getRequestHeaders().getFirst("Authorization") != null) + "}";
				byte[] data = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				e.getResponseHeaders().set("Content-Type", "application/json");
				e.sendResponseHeaders(200, data.length);
				e.getResponseBody().write(data);
				e.close();
			});
			backend.start();
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}
	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry r) {
		for (String service : List.of("hotel-service", "booking-service"))
			r.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
					() -> "http://127.0.0.1:" + backend.getAddress().getPort());
	}
	@Autowired
	WebTestClient client;
	@AfterAll
	static void stop() {
		backend.stop(0);
	}
	String token(String role) {
		var now = Instant.now();
		var claims = JwtClaimsSet.builder().issuer("stayflow").subject("1").audience(List.of("stayflow-api"))
				.issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("roles", List.of(role)).build();
		return new NimbusJwtEncoder(new ImmutableSecret<>(
				"test-only-signing-material-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
	@Test
	void allPublicPathsRouteThroughDiscovery() {
		for (String path : List.of("/user", "/user/1", "/booking", "/booking/1", "/bookings", "/api/hotels",
				"/api/hotels/1", "/api/rooms", "/api/rooms/recommend", "/api/bookings", "/api/bookings/1",
				"/api/bookings/recommend", "/bookings/recommend"))
			client.get().uri(path).header("Authorization", "Bearer " + token("USER"))
					.header("X-Trace-Id", "gateway-test").exchange().expectStatus().isOk().expectBody()
					.jsonPath("$.path").isEqualTo(path).jsonPath("$.trace").isEqualTo("gateway-test").jsonPath("$.auth")
					.isEqualTo(true);
		for (String path : List.of("/user/auth", "/user/register"))
			client.post().uri(path).exchange().expectStatus().isOk();
	}
	@Test
	void internalRoutesAreDeniedBeforeGeneralRoomRoute() {
		for (String role : List.of("USER", "ADMIN"))
			for (String op : List.of("release", "confirm-availability"))
				client.post().uri("/api/rooms/1/" + op).header("Authorization", "Bearer " + token(role)).exchange()
						.expectStatus().isForbidden().expectBody().jsonPath("$.status").isEqualTo(403);
	}
	@Test
	void errorsAndInvalidTokensHaveUniformShape() {
		client.get().uri("/bookings").exchange().expectStatus().isUnauthorized().expectHeader().exists("X-Trace-Id")
				.expectBody().jsonPath("$.traceId").exists();
		client.get().uri("/bookings").header("Authorization", "Bearer invalid").exchange().expectStatus()
				.isUnauthorized().expectBody().jsonPath("$.status").isEqualTo(401);
	}
}
