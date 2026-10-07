package com.example.leaderboard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class RedisStore {
 final StringRedisTemplate redis;
 final ObjectMapper json;
 private final String prefix;
 public RedisStore(StringRedisTemplate redis, ObjectMapper json, @Value("${leaderboard.key-prefix}") String prefix) {
  if (!prefix.matches("[A-Za-z0-9:{}_-]{1,80}")) throw new IllegalArgumentException("Invalid Redis prefix");
  this.redis=redis; this.json=json; this.prefix=prefix;
 }
 String key(String suffix) { return prefix+suffix; }
 String encode(Object value) {
  try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
 }
 <T> T decode(String value, Class<T> type) {
  try { return json.readValue(value,type); } catch (JsonProcessingException e) { throw new IllegalStateException("Stored data is invalid",e); }
 }
 @SuppressWarnings("unchecked")
 List<String> script(String source, List<String> keys, String... args) {
  return (List<String>)(List<?>) redis.execute(new DefaultRedisScript<>(source,List.class),keys,(Object[])args);
 }
 void rateLimit(String category, String identity, int maximum) {
  Long count=redis.execute(new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class),
   List.of(key("rate:"+category+":"+identity)));
  if(count==null || count>maximum) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Too many requests. Retry in 60 seconds.");
 }
}
