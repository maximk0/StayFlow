package com.stayflow.common;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
public final class SafeJsonFormatter implements StructuredLogFormatter<ILoggingEvent> {
	private final ObjectMapper json = new ObjectMapper();
	public String format(ILoggingEvent event) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
		fields.put("level", event.getLevel().toString());
		fields.put("logger", event.getLoggerName());
		fields.put("message", safe(event.getFormattedMessage()));
		event.getMDCPropertyMap().forEach((key, value) -> fields.put(key, safe(value)));
		if (event.getKeyValuePairs() != null)
			event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, safe(String.valueOf(pair.value))));
		if (event.getThrowableProxy() != null)
			fields.put("exception",
					safe(event.getThrowableProxy().getClassName() + ": " + event.getThrowableProxy().getMessage()));
		try {
			return json.writeValueAsString(fields) + System.lineSeparator();
		} catch (Exception e) {
			return "{\"level\":\"ERROR\",\"message\":\"Log serialization failed\"}" + System.lineSeparator();
		}
	}
	private String safe(String value) {
		return value == null ? null : value.replaceAll("(?i)(https?://)[^\\s/@]+@", "$1[redacted]@");
	}
}
