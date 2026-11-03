package com.stayflow.bookingservice.configuration;
import com.stayflow.bookingservice.client.HotelClient;
import com.stayflow.bookingservice.dto.BookingInput;
import com.stayflow.bookingservice.repository.UserRepository;
import com.stayflow.bookingservice.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.UUID;
@Component
@Profile("demo")
public class DemoBookings {
	private final UserRepository users;
	private final BookingService service;
	private final BookingStore store;
	private final HotelClient hotel;
	private final Clock clock;
	private boolean seeded;
	public DemoBookings(UserRepository users, BookingService service, BookingStore store, HotelClient hotel,
			Clock clock) {
		this.users = users;
		this.service = service;
		this.store = store;
		this.hotel = hotel;
		this.clock = clock;
	}
	@Scheduled(initialDelay = 10000, fixedDelay = 10000)
	public void seed() {
		String previous = org.slf4j.MDC.get("traceId");
		org.slf4j.MDC.put("traceId", java.util.UUID.randomUUID().toString());
		try {
			seedBookings();
		} finally {
			if (previous == null)
				org.slf4j.MDC.remove("traceId");
			else
				org.slf4j.MDC.put("traceId", previous);
		}
	}
	private void seedBookings() {
		if (seeded)
			return;
		try {
			var owner = users.findByUsername("demo").orElseThrow();
			var start = LocalDate.now(clock).plusDays(30);
			var candidates = hotel.recommend(start, start.plusDays(2));
			if (candidates.isEmpty())
				return;
			var input = new BookingInput(candidates.get(0).id(), false, start, start.plusDays(2));
			var confirmed = service.create(owner.id, "demo-confirmed-" + UUID.randomUUID(), input);
			var cancelled = service.create(owner.id, "demo-cancelled-" + UUID.randomUUID(),
					new BookingInput(input.roomId(), false, start.plusDays(3), start.plusDays(4)));
			service.cancel(owner.id, cancelled.id());
			store.prepare(owner.id, "demo-pending",
					new BookingInput(input.roomId(), false, start.plusDays(5), start.plusDays(6)));
			seeded = confirmed.status().equals("CONFIRMED");
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger(DemoBookings.class)
					.info("Demo bookings will be retried when Hotel is available");
		}
	}
}
