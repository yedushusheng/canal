package com.alibaba.otter.canal.adapter.launcher.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.springframework.stereotype.Component;

import com.alibaba.otter.canal.adapter.launcher.config.CuratorClient;

@Component
public class EtlLock {

    private static final Map<String, ReentrantLock>     LOCAL_LOCK       = new ConcurrentHashMap<>();

    private static final Map<String, InterProcessMutex> DISTRIBUTED_LOCK = new ConcurrentHashMap<>();

    private static Mode                                 mode             = Mode.LOCAL;

    @Resource
    private CuratorClient                               curatorClient;

    @PostConstruct
    public void init() {
        CuratorFramework curator = curatorClient.getCurator();
        if (curator != null) {
            mode = Mode.DISTRIBUTED;
        } else {
            mode = Mode.LOCAL;
        }
    }

    private ReentrantLock getLock(String key) {
        ReentrantLock lock = LOCAL_LOCK.get(key);
        if (lock == null) {
            synchronized (EtlLock.class) {
                lock = LOCAL_LOCK.get(key);
                if (lock == null) {
                    lock = new ReentrantLock();
                    LOCAL_LOCK.put(key, lock);
                }
            }
        }
        return lock;
    }

    private InterProcessMutex getRemoteLock(String key) {
        InterProcessMutex lock = DISTRIBUTED_LOCK.get(key);
        if (lock == null) {
            synchronized (EtlLock.class) {
                lock = DISTRIBUTED_LOCK.get(key);
                if (lock == null) {
                    lock = new InterProcessMutex(curatorClient.getCurator(), key);
                    DISTRIBUTED_LOCK.put(key, lock);
                }
            }
        }
        return lock;
    }

    public void lock(String key) throws Exception {
        if (mode == Mode.LOCAL) {
            getLock(key).lock();
        } else {
            InterProcessMutex lock = getRemoteLock(key);
            lock.acquire();
        }
    }

    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        try {
            if (mode == Mode.LOCAL) {
                return getLock(key).tryLock(timeout, unit);
            } else {
                InterProcessMutex lock = getRemoteLock(key);
                return lock.acquire(timeout, unit);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public boolean tryLock(String key) {
        try {
            if (mode == Mode.LOCAL) {
                return getLock(key).tryLock();
            } else {
                InterProcessMutex lock = getRemoteLock(key);
                return lock.acquire(500, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void unlock(String key) {
        if (mode == Mode.LOCAL) {
            getLock(key).unlock();
        } else {
            InterProcessMutex lock = getRemoteLock(key);
            try {
                lock.release();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}