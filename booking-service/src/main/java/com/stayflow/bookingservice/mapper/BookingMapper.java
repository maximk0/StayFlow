package com.stayflow.bookingservice.mapper;
import com.stayflow.bookingservice.entity.Booking;
import com.stayflow.bookingservice.dto.BookingView;
public final class BookingMapper {
	private BookingMapper() {
	}
	public static BookingView view(Booking b) {
		return new BookingView(b.id, b.roomId, b.startDate, b.endDate, b.status.name(), b.createdAt,
				b.compensationPending);
	}
}