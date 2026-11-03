package com.stayflow.hotelservice.entity;
import jakarta.persistence.*;
@Entity
@Table(name = "hotels")
public class Hotel {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	public Long id;
	@Column(nullable = false, length = 120)
	public String name;
	@Column(nullable = false, length = 250)
	public String address;
	public boolean deleted;
}