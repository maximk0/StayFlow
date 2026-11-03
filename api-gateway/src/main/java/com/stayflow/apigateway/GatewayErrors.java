package com.stayflow.apigateway;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.annotation.Order;
@Component
@Order(-2)
public class GatewayErrors implements WebExceptionHandler {
	private final ObjectMapper json;
	public GatewayErrors(ObjectMapper json) {
		this.json = json;
	}
	public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
		int status = error instanceof org.springframework.web.server.ResponseStatusException e
				? e.getStatusCode().value()
				: 503;
		return write(exchange, status);
	}
	public Mono<Void> write(ServerWebExchange exchange, int status) {
		if (exchange.getResponse().isCommitted())
			return Mono.empty();
		exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		try {
			byte[] body = json.writeValueAsBytes(Map.of("timestamp", Instant.now().toString(), "status", status, "code",
					HttpStatus.valueOf(status).name(), "message",
					status == 401
							? "Authentication required"
							: status == 403 ? "Access denied" : "Gateway request failed",
					"traceId", exchange.getAttributeOrDefault("traceId", "unknown")));
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
		} catch (Exception e) {
			return exchange.getResponse().setComplete();
		}
	}
}
