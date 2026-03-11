package com.fleebug.service;

import com.fleebug.config.RedisConfig;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RedisService {

    private final JedisPool jedisPool = RedisConfig.getJedisPool();

    public void publishToQueue(String queueName, String message) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(queueName, message);
        }
    }

    public void saveToCache(String key, String value, long ttl, TimeUnit timeUnit) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, timeUnit.toSeconds(ttl), value);
        }
    }

    public String getFromCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            System.err.println("[redis] GET failed for key " + key + ": " + e.getMessage());
            return null;
        }
    }

    public void deleteFromCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        }
    }

    public boolean existsInCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(key);
        } catch (Exception e) {
            return false;
        }
    }

    public long getTTL(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.ttl(key);
        } catch (Exception e) {
            return -2;
        }
    }

    public long incrementCounter(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.incr(key);
        }
    }

    public void setCounterWithTTL(String key, long ttl, TimeUnit timeUnit) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, timeUnit.toSeconds(ttl), "0");
        }
    }

    public void appendToStream(String streamKey, Map<String, String> fields) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.xadd(streamKey, StreamEntryID.NEW_ENTRY, fields);
        }
    }

    // key builders

    public static String modelKey(String modelId) {
        return "model:" + modelId;
    }

    public static String billingConfigKey(String modelId) {
        return "billing:config:" + modelId;
    }
}
