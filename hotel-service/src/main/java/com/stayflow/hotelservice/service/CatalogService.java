package com.stayflow.hotelservice.service;
import com.stayflow.hotelservice.dto.*;
import com.stayflow.hotelservice.entity.*;
import com.stayflow.hotelservice.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@Service
@Transactional
public class CatalogService {
	private final HotelRepository hotels;
	private final RoomRepository rooms;
	private final ReservationRepository reservations;
	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;
	private Hotel lockedHotel(Long id) {
		var h = hotels.lock(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found"));
		entityManager.refresh(h, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		if (h.deleted)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found");
		return h;
	}
	private Room lockedRoom(Long id) {
		var r = rooms.lock(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
		entityManager.refresh(r, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		return r;
	}
	public CatalogService(HotelRepository hotels, RoomRepository rooms, ReservationRepository reservations) {
		this.hotels = hotels;
		this.rooms = rooms;
		this.reservations = reservations;
	}
	private Hotel hotel(Long id) {
		return hotels.findById(id).filter(h -> !h.deleted)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found"));
	}
	public List<HotelView> hotels(String name) {
		return hotels.findAll().stream()
				.filter(h -> !h.deleted && (name == null || h.name.toLowerCase().contains(name.toLowerCase())))
				.sorted(Comparator.comparing(h -> h.id)).map(h -> new HotelView(h.id, h.name, h.address)).toList();
	}
	public HotelView saveHotel(Long id, HotelInput input) {
		Hotel h = id == null ? new Hotel() : hotel(id);
		h.name = input.name();
		h.address = input.address();
		h = hotels.save(h);
		return new HotelView(h.id, h.name, h.address);
	}
	public void deleteHotel(Long id) {
		Hotel h = lockedHotel(id);
		var members = rooms.findAll().stream().filter(r -> r.hotel.id.equals(id) && !r.deleted).toList();
		for (var r : members)
			deleteRoom(r.id);
		h.deleted = true;
	}
	public RoomView saveRoom(Long id, RoomInput input) {
		var hotel = lockedHotel(input.hotelId());
		Room room = id == null ? new Room() : lockedRoom(id);
		if (room.deleted)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
		if (id != null && !room.hotel.id.equals(input.hotelId()) && reservations.existsByRoomIdAndReleasedFalse(id))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot move a reserved room");
		room.hotel = hotel;
		room.number = input.number();
		room.available = input.available();
		room = rooms.save(room);
		return new RoomView(room.id, room.hotel.id, room.number, room.available, room.timesBooked);
	}
	public void deleteRoom(Long id) {
		Room r = lockedRoom(id);
		if (reservations.existsByRoomIdAndReleasedFalse(id))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Room has reservations");
		r.deleted = true;
	}
	public com.stayflow.hotelservice.dto.HotelView getHotel(Long id) {
		var h = hotel(id);
		return new HotelView(h.id, h.name, h.address);
	}
	public RoomView getRoom(Long id) {
		var r = rooms.findById(id).filter(n -> !n.deleted)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
		return new RoomView(r.id, r.hotel.id, r.number, r.available, r.timesBooked);
	}
	public OccupancyView statistics(java.time.LocalDate start, java.time.LocalDate end) {
		if (start == null || end == null || !start.isBefore(end))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid period");
		long nights = java.time.temporal.ChronoUnit.DAYS.between(start, end);
		var eligible = rooms.findAll().stream().filter(r -> !r.deleted && r.available && !r.hotel.deleted)
				.map(r -> r.id).collect(java.util.stream.Collectors.toSet());
		long occupied = reservations.findAll().stream()
				.filter(r -> !r.released && eligible.contains(r.roomId) && r.startDate.isBefore(end)
						&& r.endDate.isAfter(start))
				.mapToLong(r -> java.time.temporal.ChronoUnit.DAYS.between(
						r.startDate.isAfter(start) ? r.startDate : start, r.endDate.isBefore(end) ? r.endDate : end))
				.sum();
		long capacity = eligible.size() * nights;
		return new OccupancyView(start, end, eligible.size(), capacity, occupied,
				capacity == 0 ? 0.0 : (double) occupied / capacity);
	}
	public List<HotelView> searchHotels(String name, String sort, int page, int size) {
		validatePage(page, size);
		var ordering = switch (sort) {
			case "id" -> org.springframework.data.domain.Sort.by("id");
			case "name" -> org.springframework.data.domain.Sort.by("name", "id");
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allowed hotel sort: id, name");
		};
		org.springframework.data.jpa.domain.Specification<Hotel> filter = (root, query, cb) -> {
			var active = cb.isFalse(root.get("deleted"));
			return name == null
					? active
					: cb.and(active, cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase(java.util.Locale.ROOT)
							.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", '!'));
		};
		return hotels.findAll(filter, org.springframework.data.domain.PageRequest.of(page, size, ordering))
				.map(h -> new HotelView(h.id, h.name, h.address)).getContent();
	}
	private void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be 1..100");
	}
	public List<RoomView> searchRooms(java.time.LocalDate start, java.time.LocalDate end, Long hotelId,
			Boolean available, String sort, int page, int size) {
		validatePage(page, size);
		if ((start == null) != (end == null) || start != null && !start.isBefore(end))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a valid complete interval");
		var ordering = switch (sort) {
			case "id" -> org.springframework.data.domain.Sort.by("id");
			case "number" -> org.springframework.data.domain.Sort.by("number", "id");
			case "load" -> org.springframework.data.domain.Sort.by("timesBooked", "id");
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allowed room sort: id, number, load");
		};
		org.springframework.data.jpa.domain.Specification<Room> filter = (root, query, cb) -> {
			var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
			predicates.add(cb.isFalse(root.get("deleted")));
			predicates.add(cb.isFalse(root.get("hotel").get("deleted")));
			if (hotelId != null)
				predicates.add(cb.equal(root.get("hotel").get("id"), hotelId));
			predicates.add(cb.equal(root.get("available"), available == null ? true : available));
			if (start != null) {
				predicates.add(cb.isTrue(root.get("available")));
				var overlap = query.subquery(Integer.class);
				var reservation = overlap.from(Reservation.class);
				overlap.select(cb.literal(1)).where(cb.equal(reservation.get("roomId"), root.get("id")),
						cb.isFalse(reservation.get("released")), cb.lessThan(reservation.get("startDate"), end),
						cb.greaterThan(reservation.get("endDate"), start));
				predicates.add(cb.not(cb.exists(overlap)));
			}
			return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
		};
		return rooms.findAll(filter, org.springframework.data.domain.PageRequest.of(page, size, ordering))
				.map(r -> new RoomView(r.id, r.hotel.id, r.number, r.available, r.timesBooked)).getContent();
	}
}
