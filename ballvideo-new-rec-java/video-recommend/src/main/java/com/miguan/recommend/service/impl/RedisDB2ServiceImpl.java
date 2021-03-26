package com.miguan.recommend.service.impl;

import com.miguan.recommend.service.RedisService;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;

@Service("redisDB2Service")
public class RedisDB2ServiceImpl extends RedisService {

    @Resource(name = "recDB2Pool")
    private JedisPool jedisPool;

    @Override
    public JedisPool getJedisPool() {
        return jedisPool;
    }
}
