package com.stayflow.bookingservice.client;
import com.stayflow.bookingservice.dto.*;
import java.time.LocalDate;
import java.util.List;
public interface HotelClient {
	List<RoomView> recommend(LocalDate start, LocalDate end);
	void confirm(Long room, ReservationInput input);
	void release(Long room, ReservationInput input);
}