package com.stayflow.bookingservice.dto;
import java.time.LocalDate;
public record ReservationInput(String bookingId, String requestId, LocalDate startDate, LocalDate endDate) {
}