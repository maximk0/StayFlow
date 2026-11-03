package com.stayflow.bookingservice.dto;
public record RoomView(Long id, Long hotelId, String number, boolean available, long timesBooked) {
}