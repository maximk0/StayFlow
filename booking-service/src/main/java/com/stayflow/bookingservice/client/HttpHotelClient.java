package com.stayflow.bookingservice.client;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.security.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.slf4j.MDC;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import io.github.resilience4j.circuitbreaker.*;
@Component
public class HttpHotelClient implements HotelClient {
	private final HttpClient http;
	private final ObjectMapper json;
	private final TokenService tokens;
	private final DiscoveryClient discovery;
	private final String baseUrl;
	private final CircuitBreaker breaker;
	private final OperationBudget budget;
	public HttpHotelClient(ObjectMapper json, TokenService tokens, DiscoveryClient discovery,
			@Value("${hotel.base-url:}") String baseUrl, OperationBudget budget) {
		this.budget = budget;
		this.json = json;
		this.tokens = tokens;
		this.discovery = discovery;
		this.baseUrl = baseUrl;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
		this.breaker = CircuitBreaker.of("hotel",
				CircuitBreakerConfig.custom().slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
						.waitDurationInOpenState(Duration.ofSeconds(2)).permittedNumberOfCallsInHalfOpenState(1)
						.recordException(e -> e instanceof RemoteFailure f && f.transientFailure()).build());
	}
	public CircuitBreaker breaker() {
		return breaker;
	}
	public List<RoomView> recommend(LocalDate start, LocalDate end) {
		String body = call("GET", "/api/rooms/recommend?startDate=" + start + "&endDate=" + end, null, true);
		try {
			return json.readValue(body, json.getTypeFactory().constructCollectionType(List.class, RoomView.class));
		} catch (Exception e) {
			throw new RemoteFailure(502);
		}
	}
	public void confirm(Long room, ReservationInput input) {
		call("POST", "/api/rooms/" + room + "/confirm-availability", input, true);
	}
	public void release(Long room, ReservationInput input) {
		call("POST", "/api/rooms/" + room + "/release", input, false);
	}
	private String base() {
		if (!baseUrl.isBlank())
			return baseUrl;
		var instances = discovery.getInstances("hotel-service");
		if (instances.isEmpty())
			throw new RemoteFailure(503);
		return instances.get(0).getUri().toString();
	}
	private String call(String method, String path, Object body, boolean guarded) {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		for (int attempt = 0; attempt < 3; attempt++) {
			budget.check();
			try {
				if (guarded)
					return breaker.executeSupplier(() -> exchange(method, path, body));
				return exchange(method, path, body);
			} catch (CallNotPermittedException e) {
				throw new RemoteFailure(503);
			} catch (RemoteFailure e) {
				if (!e.transientFailure() || attempt == 2
						|| System.nanoTime() + Duration.ofMillis(900).toNanos() > deadline
						|| budget.remainingNanos() <= Duration.ofMillis(50L << attempt).toNanos())
					throw e;
				try {
					Thread.sleep(50L << attempt);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new RemoteFailure(503);
				}
			}
		}
		throw new RemoteFailure(503);
	}
	private String exchange(String method, String path, Object body) {
		try {
			budget.check();
			var builder = HttpRequest.newBuilder(URI.create(base() + path))
					.timeout(Duration
							.ofNanos(Math.max(1, Math.min(Duration.ofMillis(700).toNanos(), budget.remainingNanos()))))
					.header("Authorization", "Bearer " + tokens.internal())
					.header("X-Trace-Id",
							Optional.ofNullable(MDC.get("traceId")).orElseGet(() -> UUID.randomUUID().toString()))
					.header("Content-Type", "application/json");
			builder.method(method,
					body == null
							? HttpRequest.BodyPublishers.noBody()
							: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400)
				throw new RemoteFailure(response.statusCode());
			return response.body();
		} catch (RemoteFailure e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RemoteFailure(503);
		} catch (Exception e) {
			throw new RemoteFailure(503);
		}
	}
}