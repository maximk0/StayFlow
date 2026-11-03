package com.stayflow.bookingservice;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.stayflow.bookingservice.client.*;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.entity.*;
import com.stayflow.bookingservice.repository.*;
import com.stayflow.bookingservice.security.TokenService;
import com.stayflow.bookingservice.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@SpringBootTest(properties = {"JWT_SECRET=test-only-signing-material-at-least-32-bytes",
		"INTERNAL_JWT_SECRET=test-only-internal-material-at-least-32-bytes", "eureka.client.enabled=false",
		"spring.cloud.discovery.enabled=false", "saga.recovery-delay-ms=3600000"})
@AutoConfigureMockMvc
class BookingIntegrationTest {
	static final AtomicReference<String> mode = new AtomicReference<>("ok");
	static final AtomicInteger confirms = new AtomicInteger();
	static final AtomicInteger releases = new AtomicInteger();
	static final AtomicBoolean reserved = new AtomicBoolean();
	static HttpServer server;
	static ExecutorService serverPool = Executors.newCachedThreadPool();
	static {
		try {
			server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(serverPool);
			server.createContext("/", exchange -> {
				try {
					String path = exchange.getRequestURI().getPath();
					int status = 200;
					String body = "";
					if (path.endsWith("recommend")) {
						body = "[{\"id\":1,\"hotelId\":1,\"number\":\"101\",\"available\":true,\"timesBooked\":0}]";
					} else if (path.endsWith("confirm-availability")) {
						confirms.incrementAndGet();
						if (mode.get().equals("conflict"))
							status = 409;
						else if (mode.get().equals("fail"))
							status = 503;
						else {
							reserved.set(true);
							if (mode.get().equals("timeout")) {
								try {
									Thread.sleep(900);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
								}
							}
						}
					} else if (path.endsWith("release")) {
						releases.incrementAndGet();
						if (mode.get().equals("releaseFail"))
							status = 503;
						else
							reserved.set(false);
					}
					byte[] data = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(status, data.length);
					exchange.getResponseBody().write(data);
				} catch (java.io.IOException ignored) {
				} finally {
					exchange.close();
				}
			});
			server.start();
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}
	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("hotel.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
	}
	static final class TestClock extends Clock {
		final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}
		public Clock withZone(ZoneId zone) {
			return this;
		}
		public Instant instant() {
			return now.get();
		}
		void advance(long seconds) {
			now.updateAndGet(i -> i.plusSeconds(seconds));
		}
	}
	@TestConfiguration
	static class TimeConfig {
		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
	@Autowired
	BookingService service;
	@Autowired
	BookingStore store;
	@Autowired
	UserService users;
	@Autowired
	BookingRepository bookings;
	@Autowired
	UserRepository accounts;
	@Autowired
	HttpHotelClient hotel;
	@Autowired
	TokenService tokens;
	@Autowired
	MockMvc mvc;
	@Autowired
	ObjectMapper json;
	@Autowired
	TestClock clock;
	UserView owner;
	@BeforeEach
	void prepare() {
		mode.set("ok");
		confirms.set(0);
		releases.set(0);
		reserved.set(false);
		hotel.breaker().reset();
		owner = users.create(new UserInput("user" + UUID.randomUUID(), "strong-test-password", "USER"));
	}
	@AfterAll
	static void shutdown() {
		server.stop(0);
		serverPool.shutdownNow();
	}
	BookingInput input() {
		return new BookingInput(1L, false, LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 4));
	}
	@Test
	void confirmedAndRepeatedCancellation() {
		var b = service.create(owner.id(), "success", input());
		assertEquals("CONFIRMED", b.status());
		assertTrue(reserved.get());
		assertEquals(b.id(), service.create(owner.id(), "success", input()).id());
		service.cancel(owner.id(), b.id());
		service.cancel(owner.id(), b.id());
		assertFalse(reserved.get());
		assertEquals(1, releases.get());
	}
	@Test
	void conflictIsNotRetried() {
		mode.set("conflict");
		assertThrows(ResponseStatusException.class, () -> service.create(owner.id(), "conflict", input()));
		assertEquals(1, confirms.get());
		assertEquals("CANCELLED",
				bookings.findByUserIdAndIdempotencyKey(owner.id(), "conflict").orElseThrow().status.name());
	}
	@Test
	void exhaustedRetriesAndCircuitRecovery() {
		mode.set("fail");
		assertThrows(ResponseStatusException.class, () -> service.create(owner.id(), "failure", input()));
		assertEquals(3, confirms.get());
		assertThrows(ResponseStatusException.class, () -> service.create(owner.id(), "failure2", input()));
		assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, hotel.breaker().getState());
		mode.set("ok");
		hotel.breaker().transitionToHalfOpenState();
		hotel.confirm(1L, new ReservationInput("recovery", "recovery", input().startDate(), input().endDate()));
		assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, hotel.breaker().getState());
	}
	@Test
	void timeoutAfterReservationIsCompensated() {
		mode.set("timeout");
		assertThrows(ResponseStatusException.class, () -> service.create(owner.id(), "timeout", input()));
		assertEquals(3, confirms.get());
		assertFalse(reserved.get());
		assertFalse(bookings.findByUserIdAndIdempotencyKey(owner.id(), "timeout").orElseThrow().compensationPending);
	}
	@Test
	void releaseFailureAndRecovery() {
		var b = service.create(owner.id(), "release", input());
		mode.set("releaseFail");
		assertTrue(service.cancel(owner.id(), b.id()).compensationPending());
		assertTrue(reserved.get());
		mode.set("ok");
		clock.advance(120);
		service.recover();
		assertFalse(store.get(owner.id(), b.id()).compensationPending());
		assertFalse(reserved.get());
	}
	@Test
	void stalePendingRecovery() {
		var prepared = store.prepare(owner.id(), "stale", input());
		clock.advance(20);
		service.recover();
		assertEquals("CANCELLED", store.get(owner.id(), prepared.view().id()).status());
	}
	@Test
	void simultaneousDuplicateHasOneRemoteConfirmation() throws Exception {
		var pool = Executors.newFixedThreadPool(8);
		var barrier = new CyclicBarrier(8);
		try {
			var futures = new ArrayList<Future<BookingView>>();
			for (int i = 0; i < 8; i++)
				futures.add(pool.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					return service.create(owner.id(), "duplicate", input());
				}));
			var ids = new HashSet<String>();
			for (var f : futures)
				ids.add(f.get(10, TimeUnit.SECONDS).id());
			assertEquals(1, ids.size());
			assertEquals(1, confirms.get());
			assertThrows(ResponseStatusException.class, () -> service.create(owner.id(), "duplicate",
					new BookingInput(2L, false, input().startDate(), input().endDate())));
		} finally {
			pool.shutdownNow();
		}
	}
	@Test
	void autoSelectionIgnoresRoomAndPagination() {
		var b = service.create(owner.id(), "auto",
				new BookingInput(999L, true, input().startDate(), input().endDate()));
		assertEquals(1L, b.roomId());
		assertEquals(b.id(), service
				.create(owner.id(), "auto", new BookingInput(888L, true, input().startDate(), input().endDate())).id());
		assertEquals(1, store.list(owner.id(), 0, 1).getNumberOfElements());
		assertThrows(ResponseStatusException.class, () -> store.prepare(owner.id(), "past",
				new BookingInput(1L, false, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2))));
	}
	@Test
	void ownershipAndSecurity() throws Exception {
		var b = service.create(owner.id(), "private", input());
		mvc.perform(get("/booking/" + b.id())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/booking/" + b.id()).header("Authorization", "Bearer bad"))
				.andExpect(status().isUnauthorized());
		var other = users.create(new UserInput("other" + UUID.randomUUID(), "strong-test-password", "USER"));
		mvc.perform(get("/booking/" + b.id()).header("Authorization", "Bearer " + tokens.user(other.id(), "USER")))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/booking/" + b.id()).header("Authorization", "Bearer " + tokens.user(other.id(), "USER")))
				.andExpect(status().isNotFound());
		mvc.perform(post("/user/register").contentType("application/json")
				.content("{\"username\":\"attacker\",\"password\":\"strong-test-password\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/user").header("Authorization", "Bearer " + tokens.user(owner.id(), "USER")))
				.andExpect(status().isForbidden());
	}
	@Test
	void expiredTokenRejected() throws Exception {
		String token = tokens.user(owner.id(), "USER");
		clock.advance(4000);
		/*
		 * Decoder uses wall clock, so issue a token with an explicitly historical
		 * clock.
		 */var old = new TokenService("test-only-signing-material-at-least-32-bytes",
				"test-only-internal-material-at-least-32-bytes",
				Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
		mvc.perform(get("/bookings").header("Authorization", "Bearer " + old.user(owner.id(), "USER")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void administrativeUserLifecycleAndActiveBookingConflict() throws Exception {
		String admin = "Bearer " + tokens.user(owner.id(), "ADMIN");
		var payload = json
				.writeValueAsString(new UserInput("managed" + UUID.randomUUID(), "strong-test-password", "USER"));
		var response = mvc
				.perform(post("/user").header("Authorization", admin).contentType("application/json").content(payload))
				.andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist()).andReturn();
		long id = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
		mvc.perform(get("/user/" + id).header("Authorization", admin)).andExpect(status().isOk());
		mvc.perform(patch("/user?id=" + id).header("Authorization", admin).contentType("application/json").content(
				json.writeValueAsString(new UserInput("updated" + UUID.randomUUID(), "strong-test-password", "ADMIN"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
		var booking = service.create(id, "managed", input());
		mvc.perform(delete("/user?id=" + id).header("Authorization", admin)).andExpect(status().isConflict());
		service.cancel(id, booking.id());
		mvc.perform(delete("/user?id=" + id).header("Authorization", admin)).andExpect(status().isOk());
		mvc.perform(get("/user/" + id).header("Authorization", admin)).andExpect(status().isNotFound());
	}
	@Test
	void completedIdempotencyResultSurvivesDateBecomingPast() {
		var result = service.create(owner.id(), "historic", input());
		clock.advance(10 * 86400);
		assertEquals(result.id(), service.create(owner.id(), "historic", input()).id());
		clock.advance(-10 * 86400);
	}
	@Test
	void recoveryAndForegroundCannotResurrectCancellation() throws Exception {
		var pending = store.prepare(owner.id(), "racing-recovery", input());
		var operation = store.operation(pending.view().id());
		clock.advance(20);
		var pool = Executors.newFixedThreadPool(2);
		var barrier = new CyclicBarrier(2);
		try {
			var recovery = pool.submit(() -> {
				barrier.await(5, TimeUnit.SECONDS);
				service.recover();
				return null;
			});
			var completion = pool.submit(() -> {
				barrier.await(5, TimeUnit.SECONDS);
				store.confirm(operation.id());
				return null;
			});
			recovery.get(10, TimeUnit.SECONDS);
			completion.get(10, TimeUnit.SECONDS);
			var result = store.get(owner.id(), operation.id());
			assertTrue(java.util.Set.of("CONFIRMED", "CANCELLED").contains(result.status()));
			if (result.status().equals("CANCELLED"))
				assertEquals("CANCELLED", store.confirm(operation.id()).status());
		} finally {
			pool.shutdownNow();
		}
	}
	@Test
	void bookingAliasesAndRecommendations() throws Exception {
		String authorization = "Bearer " + tokens.user(owner.id(), "USER");
		var response = mvc
				.perform(post("/api/bookings").header("Authorization", authorization).header("Idempotency-Key", "alias")
						.contentType("application/json").content(json.writeValueAsString(input())))
				.andExpect(status().isCreated()).andReturn();
		String id = json.readTree(response.getResponse().getContentAsString()).get("id").asText();
		mvc.perform(get("/api/bookings/" + id).header("Authorization", authorization)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id));
		mvc.perform(get("/api/bookings").header("Authorization", authorization)).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/bookings/recommend?startDate=2030-01-02&endDate=2030-01-04").header("Authorization",
				authorization)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(1));
		mvc.perform(delete("/api/bookings/" + id).header("Authorization", authorization)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}
}
