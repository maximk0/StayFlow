package com.stayflow.bookingservice.dto;
public record TokenView(String accessToken, String tokenType, long expiresIn) {
}