/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.memory.pool;

import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hugegraph.memory.MemoryManager;
import org.apache.hugegraph.memory.pool.impl.MemoryPoolStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractMemoryPool implements MemoryPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMemoryPool.class);
    private final Queue<MemoryPool> children =
            new PriorityQueue<>((o1, o2) -> (int) (o2.getFreeBytes() - o1.getFreeBytes()));
    protected final MemoryManager memoryManager;
    protected final ReentrantLock arbitrationLock = new ReentrantLock();
    protected final Condition condition = arbitrationLock.newCondition();
    protected final AtomicBoolean isBeingArbitrated = new AtomicBoolean(false);
    protected final MemoryPoolStats stats;
    protected boolean isClosed = false;
    private MemoryPool parent;

    public AbstractMemoryPool(MemoryPool parent, String memoryPoolName,
                              MemoryManager memoryManager) {
        this.parent = parent;
        this.stats = new MemoryPoolStats(memoryPoolName);
        this.memoryManager = memoryManager;
    }

    @Override
    public long tryToReclaimLocalMemory(long neededBytes) {
        if (isClosed) {
            LOGGER.warn("[{}] is already closed, will abort this reclaim", this);
            return 0;
        }
        LOGGER.info("[{}] tryToReclaimLocalMemory: neededBytes={}", this, neededBytes);
        long totalReclaimedBytes = 0;
        long currentNeededBytes = neededBytes;
        try {
            this.arbitrationLock.lock();
            this.isBeingArbitrated.set(true);
            for (MemoryPool child : this.children) {
                long reclaimedMemory = child.tryToReclaimLocalMemory(currentNeededBytes);
                if (reclaimedMemory > 0) {
                    currentNeededBytes -= reclaimedMemory;
                    totalReclaimedBytes += reclaimedMemory;
                    // Reclaim enough memory.
                    if (currentNeededBytes <= 0) {
                        break;
                    }
                }
            }
            LOGGER.info("[{}] has finished to reclaim memory: totalReclaimedBytes={}, " +
                        "neededBytes={}",
                        this,
                        totalReclaimedBytes, neededBytes);
            return totalReclaimedBytes;
        } finally {
            this.stats.setNumShrinks(this.stats.getNumShrinks() + 1);
            this.stats.setAllocatedBytes(
                    this.stats.getAllocatedBytes() - totalReclaimedBytes);
            this.isBeingArbitrated.set(false);
            this.arbitrationLock.unlock();
            this.condition.signalAll();
        }
    }

    /**
     * called when one layer pool is successfully executed and exited.
     */
    @Override
    public synchronized void releaseSelf(String reason) {
        try {
            if (isBeingArbitrated.get()) {
                condition.await();
            }
            LOGGER.info("[{}] starts to releaseSelf because of {}", this, reason);
            this.isClosed = true;
            // update father
            Optional.ofNullable(parent).ifPresent(parent -> parent.gcChildPool(this, false));
            for (MemoryPool child : this.children) {
                gcChildPool(child, true);
            }
            LOGGER.info("[{}] finishes to releaseSelf", this);
        } catch (InterruptedException e) {
            LOGGER.error("Failed to release self because ", e);
            Thread.currentThread().interrupt();
        } finally {
            // Make these objs be GCed by JVM quickly.
            this.parent = null;
            this.children.clear();
        }
    }

    @Override
    public void gcChildPool(MemoryPool child, boolean force) {
        if (force) {
            child.releaseSelf(String.format("[%s] releaseChildPool", this));
        }
        // reclaim child's memory and update stats
        stats.setAllocatedBytes(
                stats.getAllocatedBytes() - child.getAllocatedBytes());
        stats.setUsedBytes(stats.getUsedBytes() - child.getUsedBytes());
        memoryManager.consumeAvailableMemory(-child.getAllocatedBytes());
        this.children.remove(child);
    }

    @Override
    public Object tryToAcquireMemoryInternal(long bytes) {
        if (isClosed) {
            LOGGER.warn("[{}] is already closed, will abort this allocate", this);
            return 0;
        }
        // just record how much memory is used(update stats)
        stats.setUsedBytes(stats.getUsedBytes() + bytes);
        stats.setCumulativeBytes(stats.getCumulativeBytes() + bytes);
        return null;
    }

    @Override
    public Object requireMemory(long bytes) {
        return null;
    }

    @Override
    public long getMaxCapacityBytes() {
        return Optional.of(stats).map(MemoryPoolStats::getMaxCapacity).orElse(0L);
    }

    @Override
    public long getUsedBytes() {
        return Optional.of(stats).map(MemoryPoolStats::getUsedBytes).orElse(0L);
    }

    @Override
    public long getFreeBytes() {
        return Optional.of(stats)
                       .map(stats -> stats.getAllocatedBytes() - stats.getUsedBytes()).orElse(0L);
    }

    @Override
    public long getAllocatedBytes() {
        return Optional.of(stats).map(MemoryPoolStats::getAllocatedBytes).orElse(0L);
    }

    @Override
    public MemoryPoolStats getSnapShot() {
        return stats;
    }

    @Override
    public MemoryPool getParentPool() {
        return parent;
    }

    @Override
    public String getName() {
        return stats.getMemoryPoolName();
    }

    @Override
    public String toString() {
        return getSnapShot().toString();
    }

    @Override
    public MemoryPool findRootQueryPool() {
        if (parent == null) {
            return this;
        }
        return getParentPool().findRootQueryPool();
    }
}
