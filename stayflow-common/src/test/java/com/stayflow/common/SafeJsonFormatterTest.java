package com.stayflow.common;
import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
class SafeJsonFormatterTest {
	@Test
	void registryCredentialsAreRedacted() {
		var event = new LoggingEvent();
		event.setLevel(Level.INFO);
		event.setLoggerName("test");
		event.setMessage("Registry http://registry:sensitive-password@localhost:8761/eureka/");
		event.setMDCPropertyMap(java.util.Map.of());
		String output = new SafeJsonFormatter().format(event);
		assertFalse(output.contains("sensitive-password"));
		assertTrue(output.contains("[redacted]@localhost"));
	}
}
