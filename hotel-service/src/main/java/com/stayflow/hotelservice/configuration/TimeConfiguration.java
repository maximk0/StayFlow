package com.stayflow.hotelservice.configuration;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class TimeConfiguration {
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}