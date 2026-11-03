package com.stayflow.hotelservice.repository;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import com.stayflow.hotelservice.entity.Room;
import java.util.Optional;
public interface RoomRepository
		extends
			JpaRepository<Room, Long>,
			org.springframework.data.jpa.repository.JpaSpecificationExecutor<Room> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Room r where r.id=:id")
	Optional<Room> lock(Long id);
}