package com.stayflow.bookingservice.configuration;
import org.springframework.context.annotation.*;
import org.springframework.boot.CommandLineRunner;
import com.stayflow.bookingservice.entity.*;
import com.stayflow.bookingservice.repository.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.UUID;
@Configuration
@Profile("demo")
public class DemoData {
	@Bean
	CommandLineRunner demo(UserRepository users, PasswordEncoder passwords,
			@Value("${DEMO_ADMIN_PASSWORD}") String adminPassword,
			@Value("${DEMO_USER_PASSWORD}") String userPassword) {
		return args -> {
			for (String role : new String[]{"ADMIN", "USER"}) {
				var u = new UserAccount();
				u.username = role.equals("ADMIN") ? "admin" : "demo";
				u.password = passwords.encode(role.equals("ADMIN") ? adminPassword : userPassword);
				u.role = role;
				users.save(u);
			}
		};
	}
}