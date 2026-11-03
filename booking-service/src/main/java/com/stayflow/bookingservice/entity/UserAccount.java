package com.stayflow.bookingservice.entity;
import jakarta.persistence.*;
@Entity
@Table(name = "users")
public class UserAccount {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	public Long id;
	@Column(nullable = false, unique = true, length = 80)
	public String username;
	@Column(nullable = false)
	public String password;
	@Column(nullable = false)
	public String role = "USER";
	public boolean deleted;
}