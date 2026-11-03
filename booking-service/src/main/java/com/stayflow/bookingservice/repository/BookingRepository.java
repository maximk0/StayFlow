package com.stayflow.bookingservice.repository;
import com.stayflow.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface BookingRepository extends JpaRepository<Booking, String> {
	List<Booking> findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(Booking.Status status, java.time.Instant cutoff);
	List<Booking> findTop50ByCompensationPendingTrueAndNextRetryLessThanEqualOrderByNextRetryAsc(java.time.Instant now);
	Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String key);
	Page<Booking> findByUserId(Long userId, Pageable pageable);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Booking b join fetch b.user where b.id=:id")
	Optional<Booking> lock(String id);
	boolean existsByUserIdAndStatusNot(Long userId, Booking.Status status);
}