package com.stayflow.bookingservice.dto;
import jakarta.validation.constraints.*;
public record Credentials(@NotBlank @Size(min = 3, max = 80) String username,
		@NotBlank @Size(min = 10, max = 72) String password, String role) {
}