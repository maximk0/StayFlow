package com.stayflow.bookingservice.dto;
import java.time.LocalDate;
import jakarta.validation.constraints.*;
public record BookingInput(Long roomId, boolean autoSelect, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
}