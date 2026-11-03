package com.stayflow.hotelservice.repository;
import org.springframework.data.jpa.repository.*;
import com.stayflow.hotelservice.entity.Reservation;
import java.time.LocalDate;
public interface ReservationRepository extends JpaRepository<Reservation, String> {
	boolean existsByRoomIdAndReleasedFalse(Long roomId);
	@Query("select count(r)>0 from Reservation r where r.roomId=:room and r.released=false and r.startDate<:end and r.endDate>:start")
	boolean overlaps(Long room, LocalDate start, LocalDate end);
}