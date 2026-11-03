package com.stayflow.bookingservice.service;
import com.stayflow.bookingservice.entity.*;
import com.stayflow.bookingservice.dto.*;
import com.stayflow.bookingservice.repository.*;
import com.stayflow.bookingservice.security.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
@Service
@Transactional
public class UserService {
	private final UserRepository users;
	private final BookingRepository bookings;
	private final PasswordEncoder passwords;
	private final TokenService tokens;
	public UserService(UserRepository users, BookingRepository bookings, PasswordEncoder passwords,
			TokenService tokens) {
		this.users = users;
		this.bookings = bookings;
		this.passwords = passwords;
		this.tokens = tokens;
	}
	public TokenView register(Credentials input) {
		if (input.role() != null && !input.role().equals("USER"))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public registration only permits USER");
		var u = create(new UserInput(input.username(), input.password(), "USER"));
		return token(u.id(), u.role());
	}
	public TokenView authenticate(Credentials input) {
		var u = users.findByUsername(input.username()).filter(n -> !n.deleted)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
		if (!passwords.matches(input.password(), u.password))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
		return token(u.id, u.role);
	}
	private TokenView token(Long id, String role) {
		return new TokenView(tokens.user(id, role), "Bearer", 3600);
	}
	public UserView create(UserInput input) {
		if (users.findByUsername(input.username()).isPresent())
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
		UserAccount u = new UserAccount();
		apply(u, input);
		return view(users.saveAndFlush(u));
	}
	public UserView update(Long id, UserInput input) {
		var u = active(id);
		apply(u, input);
		return view(u);
	}
	private void apply(UserAccount u, UserInput i) {
		u.username = i.username();
		if (i.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
		u.password = passwords.encode(i.password());
		u.role = i.role();
	}
	public UserAccount active(Long id) {
		return users.findById(id).filter(u -> !u.deleted)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}
	public List<UserView> list() {
		return users.findAll().stream().filter(u -> !u.deleted).map(this::view).toList();
	}
	public UserView get(Long id) {
		return view(active(id));
	}
	private UserView view(UserAccount u) {
		return new UserView(u.id, u.username, u.role);
	}
	public void delete(Long id) {
		var u = users.lock(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
		if (bookings.existsByUserIdAndStatusNot(id, Booking.Status.CANCELLED))
			throw new ResponseStatusException(HttpStatus.CONFLICT, "User has active bookings");
		u.deleted = true;
	}
}