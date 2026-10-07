package com.example.leaderboard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/api")
public class ApiController {
 public record Credentials(@NotBlank @Size(max=32) String username,@NotBlank @Size(max=72) String password) {
  @Override public String toString() { return "Credentials[username="+username+", password=REDACTED]"; }
 }
 private final AuthService auth;
 private final LeaderboardService boards;
 private final LiveUpdates updates;
 private final RedisStore store;
 public ApiController(AuthService auth,LeaderboardService boards,LiveUpdates updates,RedisStore store) { this.auth=auth;this.boards=boards;this.updates=updates;this.store=store; }
 @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
 public AuthService.User register(@Valid @RequestBody Credentials input,HttpServletRequest request) { return auth.register(input.username(),input.password(),request.getRemoteAddr()); }
 @PostMapping("/auth/login")
 public AuthService.Session login(@Valid @RequestBody Credentials input,HttpServletRequest request) { return auth.login(input.username(),input.password(),request.getRemoteAddr()); }
 @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void logout(HttpServletRequest request) { auth.logout(ApiConfig.identity(request)); }
 @GetMapping("/me") public AuthService.User me(HttpServletRequest request) { return auth.me(ApiConfig.identity(request)); }
 @PostMapping("/scores") public ResponseEntity<LeaderboardService.Submission> submit(@Valid @RequestBody LeaderboardService.ScoreRequest input,HttpServletRequest request) {
  var result=boards.submit(ApiConfig.identity(request).username(),input);
  return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
 }
 @GetMapping("/scores/history") public LeaderboardService.History history(HttpServletRequest request,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) {
  return boards.history(ApiConfig.identity(request).username(),offset,limit);
 }
 @GetMapping("/games") public List<String> games() { return boards.games(); }
 @GetMapping("/leaderboards") public LeaderboardService.Board board(@RequestParam(required=false) String game,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="10") int limit) {
  return boards.board(game,offset,limit);
 }
 @GetMapping("/rankings/me") public LeaderboardService.Ranking rank(HttpServletRequest request,@RequestParam(required=false) String game) {
  return boards.rank(ApiConfig.identity(request).username(),game);
 }
 @GetMapping("/reports/top-players") public LeaderboardService.Report report(@RequestParam Instant from,@RequestParam Instant to,
  @RequestParam(required=false) String game,@RequestParam(defaultValue="10") int limit) { return boards.report(from,to,game,limit); }
 @GetMapping(value="/leaderboards/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 public SseEmitter stream(HttpServletRequest request,@RequestParam(required=false) String game) { return updates.subscribe(ApiConfig.identity(request),game); }
 @GetMapping("/health") public Map<String,String> health() {
  String response=store.redis.execute((org.springframework.data.redis.core.RedisCallback<String>)connection->connection.ping());
  if(!"PONG".equals(response)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Storage is unavailable.");
  return Map.of("status","UP");
 }
}
