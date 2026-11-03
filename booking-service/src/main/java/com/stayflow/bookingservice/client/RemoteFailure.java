package com.stayflow.bookingservice.client;
public class RemoteFailure extends RuntimeException {
	private final int status;
	public RemoteFailure(int status) {
		super("Hotel operation failed");
		this.status = status;
	}
	public int status() {
		return status;
	}
	public boolean transientFailure() {
		return status >= 500;
	}
}