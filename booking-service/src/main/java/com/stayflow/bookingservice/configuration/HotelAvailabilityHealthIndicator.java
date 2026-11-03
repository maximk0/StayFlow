package com.stayflow.bookingservice.configuration;
import com.stayflow.bookingservice.client.HotelClient;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import java.time.*;
@Component
public class HotelAvailabilityHealthIndicator implements HealthIndicator {
	private final HotelClient hotel;
	private final Clock clock;
	public HotelAvailabilityHealthIndicator(HotelClient hotel, Clock clock) {
		this.hotel = hotel;
		this.clock = clock;
	}
	public Health health() {
		try {
			var start = LocalDate.now(clock).plusDays(1);
			hotel.recommend(start, start.plusDays(1));
			return Health.up().build();
		} catch (RuntimeException e) {
			return Health.down().build();
		}
	}
}
