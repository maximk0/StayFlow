package com.stayflow.hotelservice.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record ReservationInput(@NotBlank String bookingId, @NotBlank String requestId, @NotNull LocalDate startDate,
		@NotNull LocalDate endDate) {
}