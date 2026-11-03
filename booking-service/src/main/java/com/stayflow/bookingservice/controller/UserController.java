package com.stayflow.bookingservice.controller;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import java.util.List;
@RestController
public class UserController {
	private final UserService users;
	public UserController(UserService users) {
		this.users = users;
	}
	@PostMapping("/user/register")
	public TokenView register(@Valid @RequestBody Credentials input) {
		return users.register(input);
	}
	@PostMapping("/user/auth")
	public TokenView auth(@Valid @RequestBody Credentials input) {
		return users.authenticate(input);
	}
	@PostMapping("/user")
	@PreAuthorize("hasRole('ADMIN')")
	public UserView create(@Valid @RequestBody UserInput input) {
		return users.create(input);
	}
	@PatchMapping("/user")
	@PreAuthorize("hasRole('ADMIN')")
	public UserView update(@RequestParam Long id, @Valid @RequestBody UserInput input) {
		return users.update(id, input);
	}
	@DeleteMapping("/user")
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@RequestParam Long id) {
		users.delete(id);
	}
	@GetMapping("/user")
	@PreAuthorize("hasRole('ADMIN')")
	public List<UserView> list() {
		return users.list();
	}
	@GetMapping("/user/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public UserView get(@PathVariable Long id) {
		return users.get(id);
	}
}