package com.stayflow.hotelservice.entity;
import jakarta.persistence.*;
@org.hibernate.annotations.Check(constraints = "times_booked >= 0")
@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"hotel_id", "number"}))
public class Room {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	public Long id;
	@ManyToOne(optional = false)
	@JoinColumn(name = "hotel_id")
	public Hotel hotel;
	@Column(nullable = false, length = 30)
	public String number;
	public boolean available = true;
	public long timesBooked;
	public boolean deleted;
}