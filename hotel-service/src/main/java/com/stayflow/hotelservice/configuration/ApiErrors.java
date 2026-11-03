package com.stayflow.hotelservice.configuration;
import com.stayflow.common.ErrorDTO;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.MDC;
import java.time.Instant;
@RestControllerAdvice
public class ApiErrors {
	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ErrorDTO> status(ResponseStatusException e) {
		return error(e.getStatusCode().value(), e.getReason());
	}
	@ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
			org.springframework.http.converter.HttpMessageNotReadableException.class,
			org.springframework.web.bind.MissingServletRequestParameterException.class,
			org.springframework.web.bind.MissingRequestHeaderException.class})
	ResponseEntity<ErrorDTO> invalid(Exception e) {
		return error(400, "Invalid request");
	}
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorDTO> conflict(Exception e) {
		return error(409, "Resource constraint conflict");
	}
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorDTO> denied(Exception e) {
		return error(403, "Access denied");
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorDTO> unexpected(Exception e) {
		org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed traceId={}", MDC.get("traceId"), e);
		return error(500, "Internal server error");
	}
	private ResponseEntity<ErrorDTO> error(int status, String message) {
		return ResponseEntity.status(status).body(
				new ErrorDTO(Instant.now(), status, HttpStatus.valueOf(status).name(), message, MDC.get("traceId")));
	}
}