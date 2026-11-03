package com.stayflow.bookingservice.client;
import static org.junit.jupiter.api.Assertions.*;
import com.stayflow.bookingservice.dto.ReservationInput;
import com.stayflow.bookingservice.security.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
class OperationBudgetHttpTest {
	@Test
	void recommendationConsumesTheDeadlineOfConfirmation() throws Exception {
		var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		var executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		var confirms = new AtomicInteger();
		server.createContext("/", exchange -> {
			try {
				boolean confirm = exchange.getRequestURI().getPath().endsWith("confirm-availability");
				if (confirm)
					confirms.incrementAndGet();
				Thread.sleep(confirm ? 600 : 150);
				byte[] bytes = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (java.io.IOException ignored) {
			} finally {
				exchange.close();
			}
		});
		server.start();
		try {
			var budget = new OperationBudget(Duration.ofMillis(400), System::nanoTime);
			var tokens = new TokenService("test-only-signing-material-at-least-32-bytes",
					"test-only-internal-material-at-least-32-bytes", Clock.systemUTC());
			var client = new HttpHotelClient(new ObjectMapper().findAndRegisterModules(), tokens, null,
					"http://127.0.0.1:" + server.getAddress().getPort(), budget);
			var start = LocalDate.of(2030, 1, 2);
			client.recommend(start, start.plusDays(1));
			long before = System.nanoTime();
			try (var operation = budget.open()) {
				client.recommend(start, start.plusDays(1));
				assertThrows(RemoteFailure.class,
						() -> client.confirm(1L, new ReservationInput("budget", "budget", start, start.plusDays(1))));
				assertTrue(budget.remainingNanos() < Duration.ofMillis(100).toNanos());
			}
			assertEquals(1, confirms.get());
			assertTrue(System.nanoTime() - before < Duration.ofSeconds(2).toNanos());
		} finally {
			server.stop(0);
			executor.shutdownNow();
		}
	}
}
