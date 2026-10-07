package com.example.leaderboard;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
@Service
public class LiveUpdates {
 private record Client(SseEmitter emitter,AuthService.Identity identity,String game) {}
 private final Map<UUID,Client> clients=new ConcurrentHashMap<>();
 private final AuthService auth;
 private final LeaderboardService leaderboards;
 private final RedisStore store;
 private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"leaderboard-heartbeat");t.setDaemon(true);return t;});
 public LiveUpdates(AuthService auth,LeaderboardService leaderboards,RedisStore store) {
  this.auth=auth;this.leaderboards=leaderboards;this.store=store;
  heartbeat.scheduleWithFixedDelay(this::heartbeat,15,15,TimeUnit.SECONDS);
 }
 synchronized SseEmitter subscribe(AuthService.Identity identity,String game) {
  LeaderboardService.game(game);
  if(clients.size()>=1000 || clients.values().stream().filter(c->c.identity().username().equals(identity.username())).count()>=3)
   throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Live stream limit reached.");
  var emitter=new SseEmitter(1800000L); UUID id=UUID.randomUUID();
  emitter.onCompletion(()->clients.remove(id));emitter.onTimeout(()->{clients.remove(id);emitter.complete();});
  emitter.onError(e->clients.remove(id));clients.put(id,new Client(emitter,identity,game));
  try { emitter.send(SseEmitter.event().name("snapshot").data(leaderboards.board(game,0,10))); }
  catch(Exception e) { clients.remove(id);emitter.complete();throw e instanceof RuntimeException re ? re : new IllegalStateException(e); }
  return emitter;
 }
 void changed(String json) {
  LeaderboardService.ScoreEntry entry=store.decode(json,LeaderboardService.ScoreEntry.class);
  clients.forEach((id,c)->{
   if(c.game()==null || c.game().equals(entry.game()))
    send(id,c,SseEmitter.event().id(entry.submissionId().toString()).name("score").data(entry));
  });
 }
 private void send(UUID id,Client c,SseEmitter.SseEventBuilder event) {
  try {
   if(!auth.active(c.identity())) { clients.remove(id);c.emitter().complete();return; }
   c.emitter().send(event);
  } catch(Exception e) { clients.remove(id);c.emitter().complete(); }
 }
 private void heartbeat() { clients.forEach((id,c)->send(id,c,SseEmitter.event().comment("heartbeat"))); }
 @PreDestroy void close() { heartbeat.shutdownNow();clients.values().forEach(c->c.emitter().complete());clients.clear(); }
 @Configuration
 static class RedisEvents {
  @Bean ThreadPoolTaskExecutor liveExecutor() {
   var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(256);executor.setThreadNamePrefix("leaderboard-events-");return executor;
  }
  @Bean RedisMessageListenerContainer updatesContainer(RedisConnectionFactory factory,RedisStore store,LiveUpdates updates,ThreadPoolTaskExecutor liveExecutor) {
   var container=new RedisMessageListenerContainer();container.setConnectionFactory(factory);container.setTaskExecutor(liveExecutor);
   container.addMessageListener((message,pattern)->updates.changed(new String(message.getBody(),StandardCharsets.UTF_8)),new ChannelTopic(store.key("updates")));
   return container;
  }
 }
}
