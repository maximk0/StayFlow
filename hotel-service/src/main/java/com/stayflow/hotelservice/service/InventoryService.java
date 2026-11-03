package com.stayflow.hotelservice.service;
import com.stayflow.hotelservice.entity.*;
import com.stayflow.hotelservice.repository.*;
import com.stayflow.hotelservice.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
@Service
public class InventoryService {
	private final RoomRepository rooms;
	private final ReservationRepository reservations;
	private final Clock clock;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;
	public InventoryService(RoomRepository rooms, ReservationRepository reservations, Clock clock) {
		this.rooms = rooms;
		this.reservations = reservations;
		this.clock = clock;
	}
	public void validate(LocalDate start, LocalDate end) {
		if (start == null || end == null || !start.isBefore(end))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date interval");
	}
	@Transactional
	public void confirm(Long roomId, ReservationInput input) {
		validate(input.startDate(), input.endDate());
		Room room = rooms.lock(roomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
		var previous = reservations.findById(input.requestId());
		if (previous.isPresent()) {
			var r = previous.get();
			if (!r.bookingId.equals(input.bookingId()) || !r.roomId.equals(roomId)
					|| !r.startDate.equals(input.startDate()) || !r.endDate.equals(input.endDate()) || r.released)
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation operation is terminal or differs");
			return;
		}
		if (input.startDate().isBefore(LocalDate.now(clock)))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Past reservation");
		if (room.deleted || room.hotel.deleted || !room.available
				|| reservations.overlaps(roomId, input.startDate(), input.endDate()))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Room unavailable");
		Reservation r = new Reservation();
		r.requestId = input.requestId();
		r.bookingId = input.bookingId();
		r.roomId = roomId;
		r.startDate = input.startDate();
		r.endDate = input.endDate();
		r.counted = true;
		entityManager.persist(r);
		room.timesBooked++;
	}
	@Transactional
	public void release(Long roomId, ReservationInput input) {
		validate(input.startDate(), input.endDate());
		Room room = rooms.lock(roomId).orElse(null);
		Reservation r = reservations.findById(input.requestId()).orElseGet(() -> {
			Reservation n = new Reservation();
			n.requestId = input.requestId();
			n.bookingId = input.bookingId();
			n.roomId = roomId;
			n.startDate = input.startDate();
			n.endDate = input.endDate();
			return n;
		});
		if (!r.bookingId.equals(input.bookingId()) || !r.roomId.equals(roomId) || !r.startDate.equals(input.startDate())
				|| !r.endDate.equals(input.endDate()))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation identity mismatch");
		if (!r.released && r.counted && room != null) {
			room.timesBooked--;
			r.counted = false;
		}
		r.released = true;
		reservations.save(r);
	}
	@Transactional(readOnly = true)
	public List<RoomView> recommend(LocalDate start, LocalDate end) {
		validate(start, end);
		return rooms.findAll().stream()
				.filter(r -> !r.deleted && !r.hotel.deleted && r.available && !reservations.overlaps(r.id, start, end))
				.sorted(Comparator.comparingLong((Room r) -> r.timesBooked).thenComparing(r -> r.id))
				.map(r -> new RoomView(r.id, r.hotel.id, r.number, r.available, r.timesBooked)).toList();
	}
}