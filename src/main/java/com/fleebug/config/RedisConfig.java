package com.fleebug.config;

import java.time.Duration;

import com.fleebug.utility.Env;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.RedisProtocol;

public class RedisConfig {

    public static JedisPool jedisPool;

    static {

        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();

        jedisPoolConfig.setMaxTotal(20); // Maximum total connections
        jedisPoolConfig.setMaxIdle(10); // Maximum idle connections
        jedisPoolConfig.setMinIdle(5); // Minimum idle connections
        jedisPoolConfig.setTestWhileIdle(true); // Test connections during eviction
        jedisPoolConfig.setMinEvictableIdleDuration(Duration.ofMinutes(5)); // Idle >5 min can be evicted
        jedisPoolConfig.setTimeBetweenEvictionRuns(Duration.ofMinutes(1)); // Eviction thread runs every 1 min

        String redisHostName = Env.get("REDIS_HOST");
        String redisPassword = Env.get("REDIS_PASSWORD");
        String redisPortStr = Env.get("REDIS_PORT");

      
        int redisPort = Integer.parseInt(redisPortStr);

        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(redisPassword)
                .timeoutMillis(2000)
                .protocol(RedisProtocol.RESP2)
                .build();

        jedisPool = new JedisPool(jedisPoolConfig, new HostAndPort(redisHostName, redisPort), clientConfig);

    }

    public static JedisPool getJedisPool() {
        return jedisPool;
    }

}
