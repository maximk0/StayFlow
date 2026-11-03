package com.stayflow.common;
import java.time.Instant;
public record ErrorDTO(Instant timestamp, int status, String code, String message, String traceId) {
}
