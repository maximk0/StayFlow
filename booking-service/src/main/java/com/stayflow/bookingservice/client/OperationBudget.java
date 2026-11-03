package com.stayflow.bookingservice.client;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.util.function.LongSupplier;
@Component
public class OperationBudget {
	private final ThreadLocal<Long> deadline = new ThreadLocal<>();
	private final long duration;
	private final LongSupplier ticker;
	@org.springframework.beans.factory.annotation.Autowired
	public OperationBudget(@Value("${saga.operation-budget-ms:9000}") long milliseconds) {
		this(Duration.ofMillis(milliseconds), System::nanoTime);
	}
	OperationBudget(Duration duration, LongSupplier ticker) {
		if (duration.isZero() || duration.isNegative())
			throw new IllegalArgumentException("Operation budget must be positive");
		this.duration = duration.toNanos();
		this.ticker = ticker;
	}
	public Scope open() {
		Long previous = deadline.get();
		if (previous == null)
			deadline.set(ticker.getAsLong() + duration);
		return new Scope(previous);
	}
	public long remainingNanos() {
		Long end = deadline.get();
		return end == null ? Long.MAX_VALUE : Math.max(0, end - ticker.getAsLong());
	}
	public void check() {
		if (remainingNanos() == 0)
			throw new RemoteFailure(503);
	}
	public final class Scope implements AutoCloseable {
		private final Long previous;
		private Scope(Long previous) {
			this.previous = previous;
		}
		public void close() {
			if (previous == null)
				deadline.remove();
			else
				deadline.set(previous);
		}
	}
}
