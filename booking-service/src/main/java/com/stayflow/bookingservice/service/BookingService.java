package com.stayflow.bookingservice.service;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.client.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatusCode;
import org.slf4j.*;
@Service
public class BookingService {
	private static final Logger log = LoggerFactory.getLogger(BookingService.class);
	private final BookingStore store;
	private final HotelClient hotel;
	private final OperationBudget budget;
	public BookingService(BookingStore store, HotelClient hotel, OperationBudget budget) {
		this.budget = budget;
		this.store = store;
		this.hotel = hotel;
	}
	public BookingView create(Long user, String key, BookingInput input) {
		try (var scope = budget.open()) {
			return createWithinBudget(user, key, input);
		}
	}
	private BookingView createWithinBudget(Long user, String key, BookingInput input) {
		var prepared = store.prepare(user, key, input);
		if (!prepared.fresh()) {
			if (prepared.failureStatus() != 0)
				throw new ResponseStatusException(HttpStatusCode.valueOf(prepared.failureStatus()), "Booking failed");
			return prepared.view();
		}
		String id = prepared.view().id();
		try {
			if (input.autoSelect()) {
				var candidates = hotel.recommend(input.startDate(), input.endDate());
				if (candidates.isEmpty())
					throw new RemoteFailure(409);
				store.select(id, candidates.get(0).id());
			}
			var operation = store.operation(id);
			event(operation, "confirm", "PENDING");
			hotel.confirm(operation.roomId(), operation.request());
			budget.check();
			var result = store.confirm(id);
			if (!result.status().equals("CONFIRMED")) {
				compensate(id);
				throw new RemoteFailure(503);
			}
			event(operation, "confirmed", result.status());
			return result;
		} catch (RemoteFailure e) {
			store.fail(id, e.status() >= 500 ? 503 : e.status());
			compensate(id);
			throw new ResponseStatusException(HttpStatusCode.valueOf(e.status() >= 500 ? 503 : e.status()),
					"Booking could not be confirmed");
		} catch (RuntimeException e) {
			store.fail(id, 503);
			compensate(id);
			throw e;
		}
	}
	public BookingView cancel(Long user, String id) {
		try (var scope = budget.open()) {
			return cancelWithinBudget(user, id);
		}
	}
	private BookingView cancelWithinBudget(Long user, String id) {
		store.cancel(user, id);
		compensate(id);
		return store.get(user, id);
	}
	public void compensate(String id) {
		if (!store.claimCompensation(id))
			return;
		var operation = store.operation(id);
		try {
			hotel.release(operation.roomId(), operation.request());
			store.compensated(id);
			event(operation, "release", "COMPENSATED");
		} catch (RuntimeException e) {
			event(operation, "release", "COMPENSATION_PENDING");
		}
	}
	@Scheduled(fixedDelayString = "${saga.recovery-delay-ms:2000}")
	public void recover() {
		String previous = MDC.get("traceId");
		if (previous == null)
			MDC.put("traceId", java.util.UUID.randomUUID().toString());
		try {
			for (String id : store.recover())
				compensate(id);
		} finally {
			if (previous == null)
				MDC.remove("traceId");
		}
	}
	private void event(BookingStore.Operation op, String step, String status) {
		log.atInfo().addKeyValue("traceId", MDC.get("traceId")).addKeyValue("bookingId", op.id())
				.addKeyValue("requestId", op.request().requestId()).addKeyValue("step", step)
				.addKeyValue("status", status).log("booking_saga");
	}
	public java.util.List<RoomView> recommend(java.time.LocalDate start, java.time.LocalDate end) {
		if (start == null || end == null || !start.isBefore(end))
			throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid date interval");
		try (var scope = budget.open()) {
			try {
				return hotel.recommend(start, end);
			} catch (RemoteFailure e) {
				throw new ResponseStatusException(HttpStatusCode.valueOf(e.status() >= 500 ? 503 : e.status()),
						"Recommendations unavailable");
			}
		}
	}
}