package com.stayflow.bookingservice.entity;
import jakarta.persistence.*;
import java.time.*;
@org.hibernate.annotations.Check(constraints = "start_date < end_date")
@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id",
		"idempotency_key"}), indexes = {@Index(columnList = "status,createdAt"),
				@Index(columnList = "compensationPending,nextRetry")})
public class Booking {
	@Id
	public String id;
	@ManyToOne(optional = false)
	@JoinColumn(name = "user_id")
	public UserAccount user;
	public Long roomId;
	@Column(nullable = false)
	public LocalDate startDate;
	@Column(nullable = false)
	public LocalDate endDate;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	public Status status;
	@Column(nullable = false)
	public Instant createdAt;
	@Column(name = "idempotency_key", nullable = false, length = 100)
	public String idempotencyKey;
	@Column(nullable = false, length = 250)
	public String fingerprint;
	@Column(nullable = false, unique = true)
	public String requestId;
	public boolean compensationPending;
	public int compensationAttempts;
	public Instant nextRetry;
	public int failureStatus;
	public enum Status {
		PENDING, CONFIRMED, CANCELLED
	}
}