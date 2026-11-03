package com.stayflow.hotelservice.dto;
import jakarta.validation.constraints.*;
public record HotelInput(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 250) String address) {
}