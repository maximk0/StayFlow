package com.stayflow.apigateway;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Mono;
import java.util.UUID;
import org.slf4j.LoggerFactory;
@Component
@Order(-200)
public class CorrelationFilter implements WebFilter {
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String header = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
		final String trace = header != null && header.matches("[a-zA-Z0-9-]{1,64}")
				? header
				: UUID.randomUUID().toString();
		exchange.getAttributes().put("traceId", trace);
		exchange.getResponse().getHeaders().set("X-Trace-Id", trace);
		var forwarded = exchange.mutate().request(r -> r.headers(h -> h.set("X-Trace-Id", trace))).build();
		return chain.filter(forwarded).contextWrite(c -> c.put("traceId", trace))
				.doFinally(signal -> LoggerFactory.getLogger(CorrelationFilter.class).atInfo()
						.addKeyValue("traceId", trace).addKeyValue("path", exchange.getRequest().getPath().value())
						.addKeyValue("status", exchange.getResponse().getStatusCode()).log("gateway_request"));
	}
}
