package com.stayflow.hotelservice;

import static org.junit.jupiter.api.Assertions.*;
import com.stayflow.hotelservice.dto.ReservationInput;
import com.stayflow.hotelservice.entity.Hotel;
import com.stayflow.hotelservice.entity.Room;
import com.stayflow.hotelservice.repository.*;
import com.stayflow.hotelservice.service.InventoryService;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"INTERNAL_JWT_SECRET=test-only-internal-material-at-least-32-bytes",
		"JWT_SECRET=test-only-signing-material-at-least-32-bytes", "eureka.client.enabled=false",
		"spring.cloud.discovery.enabled=false"})
class InventoryServiceTest {
	@Autowired
	InventoryService inventory;
	@Autowired
	HotelRepository hotels;
	@Autowired
	RoomRepository rooms;
	private Room room() {
		Hotel h = new Hotel();
		h.name = "Test";
		h.address = "Test address";
		h = hotels.save(h);
		Room r = new Room();
		r.hotel = h;
		r.number = UUID.randomUUID().toString().substring(0, 20);
		return rooms.save(r);
	}
	private ReservationInput request(LocalDate start, LocalDate end) {
		return new ReservationInput(UUID.randomUUID().toString(), UUID.randomUUID().toString(), start, end);
	}
	@Test
	void adjacentIntervalsAndRepeatedRelease() {
		var room = room();
		var start = LocalDate.now(clock).plusDays(2);
		var first = request(start, start.plusDays(1));
		var second = request(start.plusDays(1), start.plusDays(2));
		inventory.confirm(room.id, first);
		inventory.confirm(room.id, first);
		inventory.confirm(room.id, second);
		assertEquals(2, rooms.findById(room.id).orElseThrow().timesBooked);
		inventory.release(room.id, first);
		inventory.release(room.id, first);
		assertEquals(1, rooms.findById(room.id).orElseThrow().timesBooked);
		assertThrows(ResponseStatusException.class, () -> inventory.confirm(room.id, first));
	}
	@Test
	void concurrentOverlappingReservationsHaveOneWinner() throws Exception {
		var room = room();
		var start = LocalDate.now(clock).plusDays(3);
		var barrier = new CyclicBarrier(8);
		var pool = Executors.newFixedThreadPool(8);
		try {
			var tasks = new java.util.ArrayList<Future<Boolean>>();
			for (int i = 0; i < 8; i++)
				tasks.add(pool.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					try {
						inventory.confirm(room.id, request(start, start.plusDays(2)));
						return true;
					} catch (ResponseStatusException e) {
						assertEquals(409, e.getStatusCode().value());
						return false;
					}
				}));
			int wins = 0;
			for (var task : tasks)
				if (task.get(10, TimeUnit.SECONDS))
					wins++;
			assertEquals(1, wins);
			assertEquals(1, rooms.findById(room.id).orElseThrow().timesBooked);
		} finally {
			pool.shutdownNow();
		}
	}

	@org.springframework.boot.test.context.TestConfiguration
	static class TimeConfig {
		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		java.time.Clock fixedClock() {
			return java.time.Clock.fixed(java.time.Instant.parse("2030-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
		}
	}
	@Autowired
	java.time.Clock clock;
	@Test
	void releaseBeforeConfirmIsTerminal() {
		var r = room();
		var start = LocalDate.now(clock).plusDays(3);
		var request = request(start, start.plusDays(2));
		inventory.release(r.id, request);
		assertThrows(ResponseStatusException.class, () -> inventory.confirm(r.id, request));
		assertEquals(0, rooms.findById(r.id).orElseThrow().timesBooked);
	}
	@Test
	void sequentialDistributionAndConcurrentNonOverlappingLoad() throws Exception {
		var first = room();
		var hotel = first.hotel;
		var ids = new java.util.ArrayList<Long>();
		ids.add(first.id);
		for (int i = 0; i < 2; i++) {
			var r = new Room();
			r.hotel = hotel;
			r.number = "balanced" + i;
			ids.add(rooms.save(r).id);
		}
		var base = LocalDate.now(clock).plusDays(10);
		for (int i = 0; i < 60; i++) {
			var start = base.plusDays(i * 2);
			var candidate = inventory.recommend(start, start.plusDays(1)).stream().filter(r -> ids.contains(r.id()))
					.findFirst().orElseThrow();
			inventory.confirm(candidate.id(), request(start, start.plusDays(1)));
		}
		for (Long id : ids)
			assertEquals(20, rooms.findById(id).orElseThrow().timesBooked);
		var pool = Executors.newFixedThreadPool(8);
		var barrier = new CyclicBarrier(8);
		try {
			var results = new java.util.ArrayList<Future<?>>();
			for (int i = 0; i < 24; i++) {
				final int offset = i;
				results.add(pool.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					var start = base.plusDays(300 + offset);
					inventory.confirm(first.id, request(start, start.plusDays(1)));
					return null;
				}));
			}
			for (var result : results)
				result.get(15, TimeUnit.SECONDS);
			assertEquals(44, rooms.findById(first.id).orElseThrow().timesBooked);
		} finally {
			pool.shutdownNow();
		}
	}

	@Autowired
	com.stayflow.hotelservice.service.CatalogService catalog;
	@Test
	void occupancyAndDeletionPolicy() {
		var r = room();
		var start = LocalDate.now(clock).plusDays(1000);
		var before = catalog.statistics(start, start.plusDays(4));
		var booking = request(start.plusDays(1), start.plusDays(3));
		inventory.confirm(r.id, booking);
		var occupied = catalog.statistics(start, start.plusDays(4));
		assertEquals(before.occupiedRoomNights() + 2, occupied.occupiedRoomNights());
		assertEquals((double) occupied.occupiedRoomNights() / occupied.capacityRoomNights(), occupied.occupancyRate());
		assertThrows(ResponseStatusException.class, () -> catalog.deleteRoom(r.id));
		assertThrows(ResponseStatusException.class, () -> catalog.deleteHotel(r.hotel.id));
		catalog.saveRoom(r.id, new com.stayflow.hotelservice.dto.RoomInput(r.hotel.id, r.number, false));
		var unavailable = catalog.statistics(start, start.plusDays(4));
		assertEquals(occupied.capacityRoomNights() - 4, unavailable.capacityRoomNights());
		assertEquals(before.occupiedRoomNights(), unavailable.occupiedRoomNights());
		inventory.release(r.id, booking);
		catalog.deleteHotel(r.hotel.id);
		assertThrows(ResponseStatusException.class, () -> catalog.getRoom(r.id));
	}
	@Test
	void nonexistentRoomReleaseStillPreventsLateConfirm() {
		var start = LocalDate.now(clock).plusDays(2);
		var request = request(start, start.plusDays(1));
		inventory.release(999999L, request);
		inventory.release(999999L, request);
		assertThrows(ResponseStatusException.class, () -> inventory.confirm(999999L, request));
	}
	@Test
	void databasePaginationFollowsAvailabilityAndDateFilters() {
		var first = room();
		var second = new Room();
		second.hotel = first.hotel;
		second.number = "page-second";
		second = rooms.save(second);
		var unavailable = new Room();
		unavailable.hotel = first.hotel;
		unavailable.number = "page-unavailable";
		unavailable.available = false;
		rooms.save(unavailable);
		assertEquals(java.util.List.of(first.id),
				catalog.searchRooms(null, null, first.hotel.id, null, "id", 0, 1).stream().map(r -> r.id()).toList());
		assertEquals(java.util.List.of(second.id),
				catalog.searchRooms(null, null, first.hotel.id, null, "id", 1, 1).stream().map(r -> r.id()).toList());
		assertTrue(catalog.searchRooms(null, null, first.hotel.id, null, "id", 2, 1).isEmpty());
		assertEquals(1, catalog.searchRooms(null, null, first.hotel.id, false, "id", 0, 10).size());
		var start = LocalDate.now(clock).plusDays(5);
		inventory.confirm(first.id, request(start, start.plusDays(2)));
		assertEquals(java.util.List.of(second.id),
				catalog.searchRooms(start, start.plusDays(1), first.hotel.id, null, "id", 0, 1).stream()
						.map(r -> r.id()).toList());
		assertEquals(2,
				catalog.searchRooms(start.plusDays(2), start.plusDays(3), first.hotel.id, null, "id", 0, 10).size());
	}
}
