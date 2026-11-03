package com.stayflow.bookingservice.dto;
import jakarta.validation.constraints.*;
public record UserInput(@NotBlank @Size(min = 3, max = 80) String username,
		@NotBlank @Size(min = 10, max = 72) String password, @Pattern(regexp = "USER|ADMIN") @NotNull String role) {
}