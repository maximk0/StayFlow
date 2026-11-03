package com.stayflow.bookingservice.configuration;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;
@Configuration
@EnableScheduling
public class ApplicationConfiguration {
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}