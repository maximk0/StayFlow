package com.stayflow.hotelservice.entity;
import jakarta.persistence.*;
import java.time.LocalDate;
@org.hibernate.annotations.Check(constraints = "start_date < end_date")
@Entity
@Table(name = "reservations", indexes = @Index(columnList = "roomId,startDate,endDate"))
public class Reservation {
	@Id
	public String requestId;
	@Column(nullable = false)
	public String bookingId;
	public Long roomId;
	@Column(nullable = false)
	public LocalDate startDate;
	@Column(nullable = false)
	public LocalDate endDate;
	public boolean released;
	public boolean counted;
}