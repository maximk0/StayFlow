package com.stayflow.hotelservice.dto;
import jakarta.validation.constraints.*;
public record RoomInput(@NotNull Long hotelId, @NotBlank @Size(max = 30) String number, boolean available) {
}