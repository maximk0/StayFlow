package com.stayflow.bookingservice.dto;
import java.time.*;
public record BookingView(String id, Long roomId, LocalDate startDate, LocalDate endDate, String status,
		Instant createdAt, boolean compensationPending) {
}