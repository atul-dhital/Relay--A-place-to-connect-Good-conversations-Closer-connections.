package com.example.leaderboard;
import org.springframework.http.HttpStatus;
public class ApiException extends RuntimeException {
 final HttpStatus status;
 public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
}
