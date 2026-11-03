package com.stayflow.hotelservice.configuration;
import org.springframework.context.annotation.*;
import org.springframework.boot.CommandLineRunner;
import com.stayflow.hotelservice.entity.*;
import com.stayflow.hotelservice.repository.*;
@Configuration
@Profile("demo")
public class DemoData {
	@Bean
	CommandLineRunner demo(HotelRepository hotels, RoomRepository rooms) {
		return args -> {
			for (int i = 0; i < 2; i++) {
				Hotel h = new Hotel();
				h.name = "StayFlow Hotel " + (i + 1);
				h.address = "Demo Avenue " + (i + 1);
				hotels.save(h);
				for (int j = 0; j < 4; j++) {
					Room r = new Room();
					r.hotel = h;
					r.number = "10" + j;
					r.available = j != 3;
					r.timesBooked = j;
					rooms.save(r);
				}
			}
		};
	}
}