package com.stayflow.hotelservice;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.List;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hotelapi;DB_CLOSE_DELAY=-1",
		"JWT_SECRET=test-only-signing-material-at-least-32-bytes",
		"INTERNAL_JWT_SECRET=test-only-internal-material-at-least-32-bytes", "eureka.client.enabled=false",
		"spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
class HotelApiTest {
	@Autowired
	MockMvc mvc;
	String token(String role, boolean internal, String audience) {
		var now = Instant.now();
		var claims = JwtClaimsSet.builder().issuer(internal ? "stayflow-service" : "stayflow").subject("1")
				.audience(List.of(audience)).issuedAt(now).expiresAt(now.plusSeconds(3600))
				.claim("roles", List.of(role)).build();
		return new NimbusJwtEncoder(new ImmutableSecret<>((internal
				? "test-only-internal-material-at-least-32-bytes"
				: "test-only-signing-material-at-least-32-bytes").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
	@Test
	void userAndAdminCannotInvokeInternalEndpoints() throws Exception {
		String body = "{\"bookingId\":\"1\",\"requestId\":\"1\",\"startDate\":\"2030-01-02\",\"endDate\":\"2030-01-03\"}";
		for (String role : List.of("USER", "ADMIN", "INTERNAL"))
			for (String operation : List.of("release", "confirm-availability"))
				mvc.perform(post("/api/rooms/1/" + operation).contentType("application/json").content(body)
						.header("Authorization", "Bearer " + token(role, false, "stayflow-api")))
						.andExpect(status().isForbidden());
		mvc.perform(post("/api/rooms/999/release").contentType("application/json").content(body).header("Authorization",
				"Bearer " + token("INTERNAL", true, "hotel-internal"))).andExpect(status().isOk());
	}
	@Test
	void jwtAudienceAndSignatureAreChecked() throws Exception {
		mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + token("USER", false, "wrong")))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/hotels").header("Authorization", "Bearer corrupt")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
	}
	@Test
	void adminCrudValidationAndFilters() throws Exception {
		String admin = "Bearer " + token("ADMIN", false, "stayflow-api");
		mvc.perform(post("/api/hotels").header("Authorization", admin).contentType("application/json")
				.content("{\"name\":\"\",\"address\":\"x\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/hotels").header("Authorization", "Bearer " + token("USER", false, "stayflow-api"))
				.contentType("application/json").content("{\"name\":\"Hotel\",\"address\":\"x\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/rooms?sort=arbitrary").header("Authorization", admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/hotels?size=1000").header("Authorization", admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/rooms?startDate=2030-01-01").header("Authorization", admin))
				.andExpect(status().isBadRequest());
		mvc.perform(
				get("/api/hotels/statistics?startDate=2030-01-01&endDate=2030-01-03").header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.capacityRoomNights").exists())
				.andExpect(jsonPath("$.occupancyRate").exists());
	}
	@Test
	void publishedOpenApiContainsErrorSchemaAndSeparatesInternal() throws Exception {
		mvc.perform(get("/v3/api-docs/public")).andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.ErrorDTO.properties.traceId").exists())
				.andExpect(jsonPath("$.paths['/api/rooms/{id}/release']").doesNotExist());
		mvc.perform(get("/v3/api-docs/internal")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/rooms/{id}/release']").exists())
				.andExpect(jsonPath("$.components.schemas.ErrorDTO").exists());
	}
}
