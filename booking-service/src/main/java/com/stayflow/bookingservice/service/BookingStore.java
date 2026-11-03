package com.stayflow.bookingservice.service;
import com.stayflow.bookingservice.entity.*;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.mapper.BookingMapper;
import com.stayflow.bookingservice.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
@Service
@Transactional
public class BookingStore {
	private final BookingRepository bookings;
	private final UserRepository users;
	private final Clock clock;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;
	public BookingStore(BookingRepository bookings, UserRepository users, Clock clock) {
		this.bookings = bookings;
		this.users = users;
		this.clock = clock;
	}
	public record Prepared(BookingView view, boolean fresh, int failureStatus) {
	}
	public Prepared prepare(Long user, String key, BookingInput input) {
		if (key == null || key.isBlank() || key.length() > 100)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Idempotency-Key is required (1..100 characters)");
		if (input.startDate() == null || input.endDate() == null || !input.startDate().isBefore(input.endDate())

				|| (!input.autoSelect() && (input.roomId() == null || input.roomId() < 1)))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid booking interval or room");
		var owner = users.lock(user).filter(u -> !u.deleted)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User unavailable"));
		String fingerprint = input.autoSelect() + ":" + (input.autoSelect() ? "auto" : input.roomId()) + ":"
				+ input.startDate() + ":" + input.endDate();
		var existing = bookings.findByUserIdAndIdempotencyKey(user, key);
		if (existing.isPresent()) {
			var b = existing.get();
			if (!b.fingerprint.equals(fingerprint))
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key content mismatch");
			return new Prepared(BookingMapper.view(b), false, b.failureStatus);
		}
		if (input.startDate().isBefore(LocalDate.now(clock)))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking cannot start in the past");
		var b = new Booking();
		b.id = UUID.randomUUID().toString();
		b.user = owner;
		b.roomId = input.autoSelect() ? null : input.roomId();
		b.startDate = input.startDate();
		b.endDate = input.endDate();
		b.status = Booking.Status.PENDING;
		b.createdAt = clock.instant();
		b.idempotencyKey = key;
		b.fingerprint = fingerprint;
		b.requestId = UUID.randomUUID().toString();
		bookings.saveAndFlush(b);
		transition(b, "NONE", "PENDING");
		return new Prepared(BookingMapper.view(b), true, 0);
	}
	public Operation operation(String id) {
		var b = lock(id);
		return operation(b);
	}
	private Operation operation(Booking b) {
		return new Operation(b.id, b.roomId, new ReservationInput(b.id, b.requestId, b.startDate, b.endDate));
	}
	public record Operation(String id, Long roomId, ReservationInput request) {
	}
	private Booking lock(String id) {
		var booking = bookings.lock(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
		entityManager.refresh(booking, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		return booking;
	}
	public void select(String id, Long room) {
		var b = lock(id);
		if (b.status != Booking.Status.PENDING)
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is terminal");
		b.roomId = room;
	}
	public BookingView confirm(String id) {
		var b = lock(id);
		if (b.status == Booking.Status.PENDING) {
			b.status = Booking.Status.CONFIRMED;
			transition(b, "PENDING", "CONFIRMED");
		}
		return BookingMapper.view(b);
	}
	public BookingView fail(String id, int status) {
		var b = lock(id);
		if (b.status != Booking.Status.CANCELLED) {
			String previous = b.status.name();
			b.status = Booking.Status.CANCELLED;
			transition(b, previous, "CANCELLED");
			b.compensationPending = b.roomId != null;
			b.nextRetry = clock.instant();
			b.failureStatus = status;
		}
		return BookingMapper.view(b);
	}
	public BookingView cancel(Long user, String id) {
		var b = lock(id);
		own(user, b);
		return fail(id, 0);
	}
	private void own(Long user, Booking b) {
		if (!b.user.id.equals(user))
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
	}
	public BookingView get(Long user, String id) {
		var b = lock(id);
		own(user, b);
		return BookingMapper.view(b);
	}
	public Page<BookingView> list(Long user, int page, int size) {
		if (page < 0 || size < 1 || size > 100)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
		return bookings
				.findByUserId(user,
						PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))))
				.map(BookingMapper::view);
	}
	public void compensated(String id) {
		var b = lock(id);
		if (b.status == Booking.Status.CANCELLED)
			b.compensationPending = false;
	}
	public List<String> recover() {
		var now = clock.instant();
		for (var b : bookings.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(Booking.Status.PENDING,
				now.minusSeconds(15))) {
			if (b.status == Booking.Status.PENDING && b.createdAt.isBefore(now.minusSeconds(15))) {
				var current = lock(b.id);
				if (current.status == Booking.Status.PENDING && current.createdAt.isBefore(now.minusSeconds(15)))
					fail(b.id, 503);
			}
		}
		return bookings.findTop50ByCompensationPendingTrueAndNextRetryLessThanEqualOrderByNextRetryAsc(now).stream()
				.map(b -> b.id).toList();
	}
	public boolean claimCompensation(String id) {
		var b = lock(id);
		if (!b.compensationPending || b.nextRetry != null && b.nextRetry.isAfter(clock.instant()))
			return false;
		b.compensationAttempts++;
		b.nextRetry = clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, b.compensationAttempts)));
		return true;
	}
	private void transition(Booking b, String from, String to) {
		org.slf4j.LoggerFactory.getLogger(BookingStore.class).atInfo()
				.addKeyValue("traceId", org.slf4j.MDC.get("traceId")).addKeyValue("bookingId", b.id)
				.addKeyValue("requestId", b.requestId).addKeyValue("step", "local_transition").addKeyValue("from", from)
				.addKeyValue("status", to).log("booking_transition");
	}
}