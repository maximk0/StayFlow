package com.stayflow.bookingservice.repository;
import com.stayflow.bookingservice.entity.UserAccount;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface UserRepository extends JpaRepository<UserAccount, Long> {
	Optional<UserAccount> findByUsername(String username);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u where u.id=:id")
	Optional<UserAccount> lock(Long id);
}