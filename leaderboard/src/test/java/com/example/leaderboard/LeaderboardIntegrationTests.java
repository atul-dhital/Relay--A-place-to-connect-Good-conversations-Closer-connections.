package com.example.leaderboard;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LeaderboardIntegrationTests.TestClock.class)
class LeaderboardIntegrationTests {
 static final String PREFIX="test:{"+UUID.randomUUID()+"}:";
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
  registry.add("spring.data.redis.host",()->System.getProperty("redis.test.host","localhost"));
  registry.add("spring.data.redis.port",()->Integer.parseInt(System.getProperty("redis.test.port","6379")));
  registry.add("leaderboard.key-prefix",()->PREFIX);
 }
 static class MutableClock extends Clock {
  final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
  public ZoneId getZone() { return ZoneOffset.UTC; }
  public Clock withZone(ZoneId zone) { return this; }
  public Instant instant() { return now.get(); }
 }
 @TestConfiguration static class TestClock { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
 @LocalServerPort int port;
 @Autowired RedisStore store;
 @Autowired AuthService auth;
 @Autowired MutableClock clock;
 @Autowired ObjectMapper json;
 final HttpClient http=HttpClient.newHttpClient();
 String url(String path) { return "http://localhost:"+port+"/api"+path; }
 HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
  var builder=HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
  if(token!=null) builder.header("Authorization","Bearer "+token);
  builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
 }
 JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
 String register(String name) throws Exception {
  var result=request("POST","/auth/register",null,Map.of("username",name,"password","correct-password"));
  assertEquals(201,result.statusCode(),result.body());
  var logged=request("POST","/auth/login",null,Map.of("username",name,"password","correct-password"));
  assertEquals(200,logged.statusCode(),logged.body());return tree(logged).get("token").asText();
 }
 HttpResponse<String> score(String token,String game,long points,UUID id) throws Exception {
  return request("POST","/scores",token,Map.of("game",game,"score",points,"submissionId",id));
 }
 @BeforeEach void reset() {
  clock.now.set(Instant.parse("2026-01-01T00:00:00Z"));
  // Delete only this randomly namespaced test service's keys; never FLUSHDB.
  var keys=store.redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty()) store.redis.delete(keys);
 }
 @AfterEach void cleanup() { var keys=store.redis.keys(PREFIX+"*");if(keys!=null && !keys.isEmpty()) store.redis.delete(keys); }
 @Test void accountsHaveHashedPasswordsAndRevocableTokens() throws Exception {
  assertEquals(401,request("GET","/leaderboards",null,null).statusCode());
  assertFalse(new ApiController.Credentials("alice","secret-password").toString().contains("secret-password"));
  assertFalse(new AuthService.Session("secret-token","alice",clock.instant()).toString().contains("secret-token"));
  assertEquals(400,request("POST","/auth/register",null,Map.of("username","ab","password","short")).statusCode());
  String token=register("Alice");
  assertEquals("alice",tree(request("GET","/me",token,null)).get("username").asText());
  assertEquals(405,request("GET","/auth/register",null,null).statusCode());
  assertEquals(404,request("GET","/unknown",token,null).statusCode());
  var account=store.redis.opsForValue().get(store.key("account:alice"));
  assertFalse(account.contains("correct-password"));assertTrue(account.contains("$2a$12$"));
  assertEquals(409,request("POST","/auth/register",null,Map.of("username","ALICE","password","another-password")).statusCode());
  assertEquals(401,request("POST","/auth/login",null,Map.of("username","alice","password","wrong-password")).statusCode());
  assertEquals(204,request("POST","/auth/logout",token,null).statusCode());
  assertEquals(401,request("GET","/me",token,null).statusCode());
 }
 @Test void cumulativeGameAndGlobalScoresHaveStableRanksAndHistory() throws Exception {
  String a=register("alice"),b=register("bob");
  assertTrue(tree(request("GET","/rankings/me",a,null)).get("rank").isNull());
  assertEquals(201,score(a,"chess",100,UUID.randomUUID()).statusCode());
  assertEquals(201,score(a,"running",50,UUID.randomUUID()).statusCode());
  assertEquals(201,score(b,"chess",150,UUID.randomUUID()).statusCode());
  JsonNode board=tree(request("GET","/leaderboards",a,null));
  assertEquals(2,board.get("totalPlayers").asInt());assertEquals("bob",board.get("players").get(0).get("username").asText());
  assertEquals(150,board.get("players").get(1).get("score").asLong());
  assertEquals(2,tree(request("GET","/rankings/me",a,null)).get("rank").asLong());
  assertEquals(1,tree(request("GET","/rankings/me?game=running",a,null)).get("rank").asLong());
  assertEquals(2,tree(request("GET","/scores/history",a,null)).get("total").asInt());
  assertEquals(1,tree(request("GET","/scores/history",b,null)).get("total").asInt());
  assertEquals(2,tree(request("GET","/leaderboards?offset=1&limit=1",a,null)).get("players").get(0).get("rank").asLong());
  assertEquals(2,tree(request("GET","/games",a,null)).size());
 }
 @Test void retriesAreIdempotentAndConflictingRetriesAreRejected() throws Exception {
  String token=register("retryuser");UUID id=UUID.randomUUID();
  var original=score(token,"chess",42,id);assertEquals(201,original.statusCode());
  var retry=score(token,"chess",42,id);assertEquals(200,retry.statusCode());assertTrue(tree(retry).get("replayed").asBoolean());
  assertEquals(tree(original).get("entry"),tree(retry).get("entry"));
  assertEquals(409,score(token,"chess",43,id).statusCode());
  assertEquals(409,score(token,"running",42,id).statusCode());
  assertEquals(42,tree(request("GET","/rankings/me",token,null)).get("score").asLong());
  assertEquals(1,tree(request("GET","/scores/history",token,null)).get("total").asInt());
 }
 @Test void concurrentScoresAndConcurrentRetriesDoNotLoseOrDuplicatePoints() throws Exception {
  String token=register("concurrent");var pool=Executors.newFixedThreadPool(8);
  try {
   List<Callable<Integer>> calls=new ArrayList<>();
   for(int i=0;i<20;i++) calls.add(()->score(token,"chess",1,UUID.randomUUID()).statusCode());
   for(var result:pool.invokeAll(calls)) assertEquals(201,result.get());
   UUID id=UUID.randomUUID();calls.clear();
   for(int i=0;i<10;i++) calls.add(()->score(token,"chess",1,id).statusCode());
   int accepted=0;for(var result:pool.invokeAll(calls)) { int status=result.get();assertTrue(status==200 || status==201);if(status==201) accepted++; }
   assertEquals(1,accepted);
   assertEquals(21,tree(request("GET","/rankings/me",token,null)).get("score").asLong());
   assertEquals(21,tree(request("GET","/scores/history",token,null)).get("total").asLong());
  } finally { pool.shutdownNow(); }
 }
 @Test void reportsUseServerTimeAndExclusiveEndAndCanFilterGames() throws Exception {
  String a=register("alice"),b=register("bob");
  clock.now.set(Instant.parse("2026-01-01T23:59:59.999Z"));score(a,"chess",90,UUID.randomUUID());
  clock.now.set(Instant.parse("2026-01-02T00:00:00Z"));score(a,"chess",20,UUID.randomUUID());score(b,"chess",40,UUID.randomUUID());score(a,"running",50,UUID.randomUUID());
  String period="?from=2026-01-02T00:00:00Z&to=2026-01-03T00:00:00Z";
  var global=tree(request("GET","/reports/top-players"+period,a,null));
  assertEquals(3,global.get("submissions").asInt());assertEquals("alice",global.get("players").get(0).get("username").asText());assertEquals(70,global.get("players").get(0).get("score").asLong());
  var game=tree(request("GET","/reports/top-players"+period+"&game=chess",a,null));
  assertEquals("bob",game.get("players").get(0).get("username").asText());assertEquals(2,game.get("submissions").asInt());
  assertEquals(400,request("GET","/reports/top-players?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z",a,null).statusCode());
 }
 @Test void badScoresPaginationAndNumericOverflowDoNotChangeHistory() throws Exception {
  String token=register("validation");
  assertEquals(400,score(token,"chess",-1,UUID.randomUUID()).statusCode());
  assertEquals(400,score(token,"chess",1000001,UUID.randomUUID()).statusCode());
  assertEquals(400,score(token,"Bad game",10,UUID.randomUUID()).statusCode());
  assertEquals(400,request("POST","/scores",token,Map.of("game","chess","score",10,"submissionId",UUID.randomUUID(),"username","forged")).statusCode());
  assertEquals(400,request("GET","/leaderboards?limit=0",token,null).statusCode());
  store.redis.opsForZSet().add(store.key("board:global"),"validation",(double)LeaderboardService.MAX_TOTAL);
  assertEquals(422,score(token,"chess",1,UUID.randomUUID()).statusCode());
  assertEquals(0,tree(request("GET","/scores/history",token,null)).get("total").asLong());
 }
 @Test void tokensExpireAndRateLimitReturnsRetryAfter() throws Exception {
  String token=register("limited");
  var identity=auth.authenticate("Bearer "+token);
  store.redis.expire(store.key("session:"+identity.tokenHash()),Duration.ofMillis(1));
  org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->!auth.active(identity));
  assertEquals(401,request("GET","/me",token,null).statusCode());
  token=tree(request("POST","/auth/login",null,Map.of("username","limited","password","correct-password"))).get("token").asText();
  store.redis.opsForValue().set(store.key("rate:score:limited"),"120",Duration.ofSeconds(60));
  var rejected=score(token,"chess",1,UUID.randomUUID());assertEquals(429,rejected.statusCode());assertEquals("60",rejected.headers().firstValue("Retry-After").orElseThrow());
 }
 @Test void liveStreamReceivesSnapshotAndRedisPublishedScore() throws Exception {
  String token=register("streamuser");
  var response=http.send(HttpRequest.newBuilder(URI.create(url("/leaderboards/stream?game=chess"))).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
  assertEquals(200,response.statusCode());
  var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8));var pool=Executors.newSingleThreadExecutor();
  try {
   var snapshot=pool.submit(()->readEvent(reader,"snapshot"));assertTrue(snapshot.get(5,TimeUnit.SECONDS).contains("totalPlayers"));
   var next=pool.submit(()->readEvent(reader,"score"));UUID id=UUID.randomUUID();
   score(token,"running",999,UUID.randomUUID());score(token,"chess",123,id);
   String event=next.get(5,TimeUnit.SECONDS);assertTrue(event.contains(id.toString()));assertTrue(event.contains("streamuser"));assertTrue(event.contains("123"));
   var afterRetry=pool.submit(()->readEvent(reader,"score"));score(token,"chess",123,id);
   UUID nextId=UUID.randomUUID();score(token,"chess",3,nextId);assertTrue(afterRetry.get(5,TimeUnit.SECONDS).contains(nextId.toString()));
   request("POST","/auth/logout",token,null);
   // A subsequent Redis event checks stream token revocation before writing.
   String other=register("otheruser");score(other,"chess",1,UUID.randomUUID());
   var eof=pool.submit(reader::readLine);assertNull(eof.get(5,TimeUnit.SECONDS));
  } finally { reader.close();pool.shutdownNow(); }
 }
 static String readEvent(BufferedReader reader,String name) throws IOException {
  String line;boolean matching=false;StringBuilder data=new StringBuilder();
  while((line=reader.readLine())!=null) {
   if(line.equals("event:"+name)) matching=true;
   if(matching && line.startsWith("data:")) data.append(line.substring(5));
   if(line.isEmpty() && matching) return data.toString();
  }
  throw new EOFException("Expected event "+name);
 }
}
