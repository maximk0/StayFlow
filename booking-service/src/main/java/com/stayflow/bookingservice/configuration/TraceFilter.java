package com.stayflow.bookingservice.configuration;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.slf4j.MDC;
import java.util.UUID;
import java.io.IOException;
@Component
@Order(-200)
public class TraceFilter extends OncePerRequestFilter {
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String trace = request.getHeader("X-Trace-Id");
		if (trace == null || !trace.matches("[a-zA-Z0-9-]{1,64}"))
			trace = UUID.randomUUID().toString();
		MDC.put("traceId", trace);
		response.setHeader("X-Trace-Id", trace);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove("traceId");
		}
	}
}