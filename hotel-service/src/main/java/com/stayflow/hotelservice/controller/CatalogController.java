package com.stayflow.hotelservice.controller;
import com.stayflow.hotelservice.service.*;
import com.stayflow.hotelservice.dto.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
@RestController
@RequestMapping("/api")
public class CatalogController {
	private final CatalogService catalog;
	private final InventoryService inventory;
	public CatalogController(CatalogService catalog, InventoryService inventory) {
		this.catalog = catalog;
		this.inventory = inventory;
	}
	@GetMapping("/hotels")
	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	public List<HotelView> hotels(@RequestParam(required = false) String name,
			@RequestParam(defaultValue = "id") String sort, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return catalog.searchHotels(name, sort, page, size);
	}
	@PostMapping("/hotels")
	@PreAuthorize("hasRole('ADMIN')")
	public HotelView createHotel(@Valid @RequestBody HotelInput input) {
		return catalog.saveHotel(null, input);
	}
	@PutMapping("/hotels/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public HotelView updateHotel(@PathVariable Long id, @Valid @RequestBody HotelInput input) {
		return catalog.saveHotel(id, input);
	}
	@DeleteMapping("/hotels/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public void deleteHotel(@PathVariable Long id) {
		catalog.deleteHotel(id);
	}
	@GetMapping("/rooms")
	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	public List<RoomView> rooms(@RequestParam(required = false) LocalDate startDate,
			@RequestParam(required = false) LocalDate endDate, @RequestParam(required = false) Long hotelId,
			@RequestParam(required = false) Boolean available, @RequestParam(defaultValue = "id") String sort,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return catalog.searchRooms(startDate, endDate, hotelId, available, sort, page, size);
	}
	@GetMapping("/rooms/recommend")
	@PreAuthorize("hasAnyRole('USER','ADMIN','INTERNAL')")
	public List<RoomView> recommend(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
		return inventory.recommend(startDate, endDate);
	}
	@PostMapping("/rooms")
	@PreAuthorize("hasRole('ADMIN')")
	public RoomView createRoom(@Valid @RequestBody RoomInput input) {
		return catalog.saveRoom(null, input);
	}
	@PutMapping("/rooms/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public RoomView updateRoom(@PathVariable Long id, @Valid @RequestBody RoomInput input) {
		return catalog.saveRoom(id, input);
	}
	@DeleteMapping("/rooms/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public void deleteRoom(@PathVariable Long id) {
		catalog.deleteRoom(id);
	}
	@PostMapping("/rooms/{id}/confirm-availability")
	@PreAuthorize("hasRole('INTERNAL')")
	public void confirm(@PathVariable Long id, @Valid @RequestBody ReservationInput input) {
		inventory.confirm(id, input);
	}
	@PostMapping("/rooms/{id}/release")
	@PreAuthorize("hasRole('INTERNAL')")
	public void release(@PathVariable Long id, @Valid @RequestBody ReservationInput input) {
		inventory.release(id, input);
	}
	@GetMapping("/hotels/{id}")
	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	public HotelView hotel(@PathVariable Long id) {
		return catalog.getHotel(id);
	}
	@GetMapping("/rooms/{id}")
	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	public RoomView room(@PathVariable Long id) {
		return catalog.getRoom(id);
	}
	@GetMapping("/hotels/statistics")
	@PreAuthorize("hasRole('ADMIN')")
	public OccupancyView statistics(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate) {
		return catalog.statistics(startDate, endDate);
	}
}
