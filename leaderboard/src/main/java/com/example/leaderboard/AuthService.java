package com.example.leaderboard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
@Service
public class AuthService {
 public record Account(String username, String passwordHash, Instant createdAt) {}
 public record User(String username, Instant createdAt) {}
 public record Session(String token, String username, Instant expiresAt) {
  @Override public String toString() { return "Session[username="+username+", token=REDACTED, expiresAt="+expiresAt+"]"; }
 }
 public record Identity(String username, String tokenHash) {}
 private final RedisStore store;
 private final Clock clock;
 private final long sessionSeconds;
 private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
 private final String dummy=passwords.encode("nonexistent-account-password");
 private final SecureRandom random=new SecureRandom();
 public AuthService(RedisStore store, Clock clock, @Value("${leaderboard.session-seconds}") long sessionSeconds) {
  if(sessionSeconds<60 || sessionSeconds>604800) throw new IllegalArgumentException("Session duration must be 60..604800 seconds");
  this.store=store; this.clock=clock; this.sessionSeconds=sessionSeconds;
 }
 static String username(String value) {
  if(value==null || !value.matches("[A-Za-z0-9_-]{3,32}")) throw new ApiException(HttpStatus.BAD_REQUEST,"Username must contain 3-32 letters, numbers, underscores or hyphens.");
  return value.toLowerCase(Locale.ROOT);
 }
 static void password(String value) {
  if(value==null || value.length()<8 || value.getBytes(StandardCharsets.UTF_8).length>72) throw new ApiException(HttpStatus.BAD_REQUEST,"Password must contain at least 8 characters and at most 72 UTF-8 bytes.");
 }
 User register(String name, String password, String address) {
  name=username(name); password(password); store.rateLimit("register",address,10);
  Account account=new Account(name,passwords.encode(password),clock.instant());
  if(!Boolean.TRUE.equals(store.redis.opsForValue().setIfAbsent(store.key("account:"+name),store.encode(account))))
   throw new ApiException(HttpStatus.CONFLICT,"Username already registered.");
  return new User(name,account.createdAt());
 }
 Session login(String name, String password, String address) {
  name=username(name); password(password); store.rateLimit("login",address,30);
  String value=store.redis.opsForValue().get(store.key("account:"+name));
  Account account=value==null ? null : store.decode(value,Account.class);
  boolean valid=passwords.matches(password,account==null ? dummy : account.passwordHash());
  if(!valid || account==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid username or password.");
  byte[] bytes=new byte[32]; random.nextBytes(bytes);
  String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  store.redis.opsForValue().set(store.key("session:"+hash(token)),name,Duration.ofSeconds(sessionSeconds));
  return new Session(token,name,clock.instant().plusSeconds(sessionSeconds));
 }
 Identity authenticate(String authorization) {
  if(authorization==null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}"))
   throw new ApiException(HttpStatus.UNAUTHORIZED,"Valid Bearer token required.");
  String digest=hash(authorization.substring(7));
  String name=store.redis.opsForValue().get(store.key("session:"+digest));
  if(name==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"Session expired or revoked.");
  return new Identity(name,digest);
 }
 boolean active(Identity identity) { return identity.username().equals(store.redis.opsForValue().get(store.key("session:"+identity.tokenHash()))); }
 void logout(Identity identity) { store.redis.delete(store.key("session:"+identity.tokenHash())); }
 User me(Identity identity) {
  Account account=store.decode(store.redis.opsForValue().get(store.key("account:"+identity.username())),Account.class);
  return new User(account.username(),account.createdAt());
 }
 private static String hash(String token) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
  catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
}
