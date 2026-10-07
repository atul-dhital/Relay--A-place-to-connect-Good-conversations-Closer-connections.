package com.example.leaderboard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
@SpringBootApplication
public class LeaderboardApplication {
 public static void main(String[] args) { SpringApplication.run(LeaderboardApplication.class, args); }
 @Bean Clock clock() { return Clock.systemUTC(); }
}
