package com.stayflow.bookingservice.controller;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import org.springframework.http.*;
@RestController
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class BookingController {
	private final BookingService service;
	private final BookingStore store;
	public BookingController(BookingService service, BookingStore store) {
		this.service = service;
		this.store = store;
	}
	@io.swagger.v3.oas.annotations.responses.ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Confirmed or replayed terminal booking", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = BookingView.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Original request still pending", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = BookingView.class)))})
	@PostMapping({"/booking", "/api/bookings"})
	public ResponseEntity<BookingView> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody BookingInput input) {
		var result = service.create(Long.valueOf(jwt.getSubject()), key, input);
		return ResponseEntity.status(result.status().equals("PENDING") ? 202 : 201).body(result);
	}
	@GetMapping({"/bookings", "/api/bookings"})
	public Page<BookingView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return store.list(Long.valueOf(jwt.getSubject()), page, size);
	}
	@GetMapping({"/booking/{id}", "/api/bookings/{id}"})
	public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return store.get(Long.valueOf(jwt.getSubject()), id);
	}
	@DeleteMapping({"/booking/{id}", "/api/bookings/{id}"})
	public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return service.cancel(Long.valueOf(jwt.getSubject()), id);
	}
	@GetMapping({"/bookings/recommend", "/api/bookings/recommend"})
	public java.util.List<RoomView> recommend(@RequestParam java.time.LocalDate startDate,
			@RequestParam java.time.LocalDate endDate) {
		return service.recommend(startDate, endDate);
	}
}