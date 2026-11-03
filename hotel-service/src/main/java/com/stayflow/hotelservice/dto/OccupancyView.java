package com.stayflow.hotelservice.dto;
import java.time.LocalDate;
public record OccupancyView(LocalDate startDate, LocalDate endDate, int operationalRooms, long capacityRoomNights,
		long occupiedRoomNights, double occupancyRate) {
}