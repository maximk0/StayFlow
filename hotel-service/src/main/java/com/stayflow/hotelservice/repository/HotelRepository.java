package com.stayflow.hotelservice.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.stayflow.hotelservice.entity.Hotel;
public interface HotelRepository
		extends
			JpaRepository<Hotel, Long>,
			org.springframework.data.jpa.repository.JpaSpecificationExecutor<Hotel> {
	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select h from Hotel h where h.id=:id")
	java.util.Optional<Hotel> lock(Long id);
}