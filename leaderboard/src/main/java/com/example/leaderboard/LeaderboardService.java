package com.example.leaderboard;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service
public class LeaderboardService {
 public record ScoreRequest(@NotNull UUID submissionId, @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9_-]{0,39}") String game, @NotNull @Min(1) @Max(1000000) Long score) {}
 public record ScoreEntry(UUID submissionId, String username, String game, long score, Instant submittedAt) {}
 public record Submission(ScoreEntry entry, long globalScore, long gameScore, boolean replayed) {}
 public record Player(long rank, String username, long score) {}
 public record Board(String game, long totalPlayers, List<Player> players) {}
 public record Ranking(String game, String username, Long rank, Long score, long totalPlayers) {}
 public record History(long total, List<ScoreEntry> entries) {}
 public record Report(Instant from, Instant to, String game, long submissions, List<Player> players) {}
 private final RedisStore store;
 private final Clock clock;
 static final long MAX_TOTAL=9_000_000_000_000_000L;
 private static final String SUBMIT="""
 local old=redis.call('HGET',KEYS[7],ARGV[1])
 if old then
   local receipt=cjson.decode(old)
   if receipt.fingerprint~=ARGV[2] then return {'CONFLICT'} end
   return {'REPLAY',receipt.entry,receipt.globalScore,receipt.gameScore}
 end
 local global=tonumber(redis.call('ZSCORE',KEYS[1],ARGV[3]) or '0')
 local game=tonumber(redis.call('ZSCORE',KEYS[2],ARGV[3]) or '0')
 local points=tonumber(ARGV[4])
 if global+points>9000000000000000 or game+points>9000000000000000 then return {'OVERFLOW'} end
 local globalScore=redis.call('ZINCRBY',KEYS[1],points,ARGV[3])
 local gameScore=redis.call('ZINCRBY',KEYS[2],points,ARGV[3])
 redis.call('ZADD',KEYS[3],ARGV[5],ARGV[6])
 redis.call('ZADD',KEYS[4],ARGV[5],ARGV[6])
 redis.call('ZADD',KEYS[5],ARGV[5],ARGV[6])
 redis.call('HSET',KEYS[6],ARGV[6],ARGV[7])
 redis.call('HSET',KEYS[7],ARGV[1],cjson.encode({fingerprint=ARGV[2],entry=ARGV[7],globalScore=globalScore,gameScore=gameScore}))
 redis.call('SADD',KEYS[8],ARGV[8])
 redis.call('PUBLISH',KEYS[9],ARGV[7])
 return {'ACCEPTED',ARGV[7],globalScore,gameScore}
 """;
 public LeaderboardService(RedisStore store,Clock clock) { this.store=store;this.clock=clock; }
 static String game(String game) {
  if(game!=null && !game.matches("[a-z0-9][a-z0-9_-]{0,39}")) throw new ApiException(HttpStatus.BAD_REQUEST,"Game must be a lowercase slug of 1-40 characters.");
  return game;
 }
 private String boardKey(String game) { return store.key(game==null ? "board:global" : "board:game:"+game); }
 static void page(int offset,int limit) {
  if(offset<0 || offset>10000 || limit<1 || limit>100) throw new ApiException(HttpStatus.BAD_REQUEST,"Offset must be 0-10000 and limit 1-100.");
 }
 Submission submit(String username,ScoreRequest input) {
  game(input.game()); store.rateLimit("score",username,120);
  ScoreEntry entry=new ScoreEntry(input.submissionId(),username,input.game(),input.score(),Instant.ofEpochMilli(clock.millis()));
  String id=input.submissionId().toString(), eventId=username+":"+id;
  List<String> result=store.script(SUBMIT,List.of(boardKey(null),boardKey(input.game()),store.key("history:all"),store.key("history:game:"+input.game()),
   store.key("history:user:"+username),store.key("events"),store.key("receipts:"+username),store.key("games"),store.key("updates")),
   id,input.game()+":"+input.score(),username,input.score().toString(),Long.toString(entry.submittedAt().toEpochMilli()),eventId,store.encode(entry),input.game());
  if("CONFLICT".equals(result.get(0))) throw new ApiException(HttpStatus.CONFLICT,"Submission ID was already used for different score data.");
  if("OVERFLOW".equals(result.get(0))) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"Maximum cumulative score reached.");
  return new Submission(store.decode(result.get(1),ScoreEntry.class),new java.math.BigDecimal(result.get(2)).longValueExact(),
   new java.math.BigDecimal(result.get(3)).longValueExact(),"REPLAY".equals(result.get(0)));
 }
 Board board(String game,int offset,int limit) {
  game(game); page(offset,limit);
  List<String> result=store.script("""
   local result={tostring(redis.call('ZCARD',KEYS[1]))}
   local values=redis.call('ZREVRANGE',KEYS[1],ARGV[1],ARGV[2],'WITHSCORES')
   for _,value in ipairs(values) do table.insert(result,value) end
   return result
   """,List.of(boardKey(game)),Integer.toString(offset),Integer.toString(offset+limit-1));
  List<Player> players=new ArrayList<>();
  for(int i=1;i<result.size();i+=2) players.add(new Player(offset+(i+1)/2,result.get(i),new java.math.BigDecimal(result.get(i+1)).longValueExact()));
  return new Board(game==null ? "global" : game,Long.parseLong(result.get(0)),players);
 }
 Ranking rank(String username,String game) {
  game(game);
  List<String> result=store.script("""
   local score=redis.call('ZSCORE',KEYS[1],ARGV[1])
   local count=tostring(redis.call('ZCARD',KEYS[1]))
   if not score then return {'','',count} end
   return {tostring(redis.call('ZREVRANK',KEYS[1],ARGV[1])+1),score,count}
   """,List.of(boardKey(game)),username);
  return new Ranking(game==null ? "global" : game,username,result.get(0).isEmpty() ? null : Long.valueOf(result.get(0)),
   result.get(1).isEmpty() ? null : new java.math.BigDecimal(result.get(1)).longValueExact(),Long.parseLong(result.get(2)));
 }
 History history(String username,int offset,int limit) {
  page(offset,limit);
  List<String> result=store.script("""
   local result={tostring(redis.call('ZCARD',KEYS[1]))}
   local ids=redis.call('ZREVRANGE',KEYS[1],ARGV[1],ARGV[2])
   for _,id in ipairs(ids) do table.insert(result,redis.call('HGET',KEYS[2],id)) end
   return result
   """,List.of(store.key("history:user:"+username),store.key("events")),Integer.toString(offset),Integer.toString(offset+limit-1));
  return new History(Long.parseLong(result.get(0)),result.subList(1,result.size()).stream().map(value->store.decode(value,ScoreEntry.class)).toList());
 }
 List<String> games() {
  Set<String> games=store.redis.opsForSet().members(store.key("games"));return games==null ? List.of() : games.stream().sorted().toList();
 }
 Report report(Instant from,Instant to,String game,int limit) {
  game(game); page(0,limit);
  if(from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0 ||
    from.getNano()%1000000!=0 || to.getNano()%1000000!=0)
   throw new ApiException(HttpStatus.BAD_REQUEST,"Use a nonempty period up to 31 days with millisecond precision; from is inclusive, to exclusive.");
  if(from.isBefore(Instant.EPOCH) || to.isAfter(Instant.parse("9999-12-31T00:00:00Z")))
   throw new ApiException(HttpStatus.BAD_REQUEST,"Report dates are outside the supported range.");
  List<String> result=store.script("""
   local count=redis.call('ZCOUNT',KEYS[1],ARGV[1],ARGV[2])
   if count>10000 then return {'TOO_LARGE'} end
   local result={}
   local ids=redis.call('ZRANGEBYSCORE',KEYS[1],ARGV[1],ARGV[2])
   for _,id in ipairs(ids) do table.insert(result,redis.call('HGET',KEYS[2],id)) end
   return result
   """,List.of(store.key(game==null ? "history:all" : "history:game:"+game),store.key("events")),Long.toString(from.toEpochMilli()),"("+to.toEpochMilli());
  if(result.size()==1 && result.get(0).equals("TOO_LARGE")) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"Report exceeds 10000 submissions. Choose a shorter period.");
  Map<String,Long> totals=new HashMap<>();
  result.forEach(value->{ScoreEntry entry=store.decode(value,ScoreEntry.class);totals.merge(entry.username(),entry.score(),Math::addExact);});
  var sorted=totals.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.<String,Long>comparingByKey().reversed())).limit(limit).toList();
  List<Player> players=new ArrayList<>();
  for(int i=0;i<sorted.size();i++) players.add(new Player(i+1,sorted.get(i).getKey(),sorted.get(i).getValue()));
  return new Report(from,to,game==null ? "global" : game,result.size(),players);
 }
}
