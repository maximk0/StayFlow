package com.stayflow.bookingservice.client;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class OperationBudgetTest {
	@Test
	void nestedStagesShareDeadlineAndCleanupDoesNotResetOuterScope() {
		var time = new AtomicLong();
		var budget = new OperationBudget(Duration.ofSeconds(1), time::get);
		try (var outer = budget.open()) {
			time.set(600_000_000);
			try (var inner = budget.open()) {
				assertEquals(400_000_000, budget.remainingNanos());
				time.set(1_000_000_000);
				assertThrows(RemoteFailure.class, budget::check);
			}
			assertThrows(RemoteFailure.class, budget::check);
		}
		try (var next = budget.open()) {
			assertEquals(1_000_000_000, budget.remainingNanos());
		}
	}
}
