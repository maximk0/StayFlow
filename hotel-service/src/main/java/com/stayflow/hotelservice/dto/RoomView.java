package com.stayflow.hotelservice.dto;
public record RoomView(Long id, Long hotelId, String number, boolean available, long timesBooked) {
}