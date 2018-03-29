/**
 * Copyright 2018 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.api.NodeType;
import org.redisson.api.RFuture;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.cluster.ClusterSlotRange;
import org.redisson.config.BaseMasterSlaveServersConfig;
import org.redisson.config.Config;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.SentinelServersConfig;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.misc.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.PlatformDependent;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class SentinelConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ConcurrentMap<String, RedisClient> sentinels = PlatformDependent.newConcurrentHashMap();
    private final AtomicReference<String> currentMaster = new AtomicReference<String>();
    private final Set<String> slaves = Collections.newSetFromMap(PlatformDependent.<String, Boolean>newConcurrentHashMap());

    private final Set<URI> disconnectedSlaves = new HashSet<URI>();
    private ScheduledFuture<?> monitorFuture;
    private AddressResolver<InetSocketAddress> sentinelResolver;

    public SentinelConnectionManager(SentinelServersConfig cfg, Config config, UUID id) {
        super(config, id);
        
        if (cfg.getMasterName() == null) {
            throw new IllegalArgumentException("masterName parameter is not defined!");
        }

        this.config = create(cfg);
        initTimer(this.config);
        
        this.sentinelResolver = resolverGroup.getResolver(getGroup().next());
        
        for (URI addr : cfg.getSentinelAddresses()) {
            RedisClient client = createClient(NodeType.SENTINEL, addr, this.config.getConnectTimeout(), this.config.getRetryInterval() * this.config.getRetryAttempts(), null);
            try {
                RedisConnection connection = client.connect();
                if (!connection.isActive()) {
                    continue;
                }

                List<String> master = connection.sync(RedisCommands.SENTINEL_GET_MASTER_ADDR_BY_NAME, cfg.getMasterName());
                String masterHost = createAddress(master.get(0), master.get(1));
                this.config.setMasterAddress(masterHost);
                currentMaster.set(masterHost);
                log.info("master: {} added", masterHost);
                slaves.add(masterHost);

                List<Map<String, String>> sentinelSlaves = connection.sync(StringCodec.INSTANCE, RedisCommands.SENTINEL_SLAVES, cfg.getMasterName());
                for (Map<String, String> map : sentinelSlaves) {
                    if (map.isEmpty()) {
                        continue;
                    }

                    String ip = map.get("ip");
                    String port = map.get("port");
                    String flags = map.get("flags");

                    String host = createAddress(ip, port);

                    this.config.addSlaveAddress(host);
                    slaves.add(host);
                    log.debug("slave {} state: {}", host, map);
                    log.info("slave: {} added", host);

                    if (flags.contains("s_down") || flags.contains("disconnected")) {
                        URI uri = URIBuilder.create(host);
                        disconnectedSlaves.add(uri);
                        log.warn("slave: {} is down", host);
                    }
                }
                
                List<Map<String, String>> sentinelSentinels = connection.sync(StringCodec.INSTANCE, RedisCommands.SENTINEL_SENTINELS, cfg.getMasterName());
                List<RFuture<Void>> connectionFutures = new ArrayList<RFuture<Void>>(sentinelSentinels.size());
                for (Map<String, String> map : sentinelSentinels) {
                    if (map.isEmpty()) {
                        continue;
                    }

                    String ip = map.get("ip");
                    String port = map.get("port");

                    String host = createAddress(ip, port);
                    URI sentinelAddr = URIBuilder.create(host);
                    RFuture<Void> future = registerSentinel(sentinelAddr, this.config);
                    connectionFutures.add(future);
                }
                
                for (RFuture<Void> future : connectionFutures) {
                    future.awaitUninterruptibly(this.config.getConnectTimeout());
                }

                break;
            } catch (RedisConnectionException e) {
                log.warn("Can't connect to sentinel server. {}", e.getMessage());
            } finally {
                client.shutdownAsync();
            }
        }

        if (currentMaster.get() == null) {
            stopThreads();
            throw new RedisConnectionException("Can't connect to servers!");
        }
        
        initSingleEntry();
        
        scheduleChangeCheck(cfg, null);
    }
    
    @Override
    protected void startDNSMonitoring(RedisClient masterHost) {
        if (config.getDnsMonitoringInterval() == -1) {
            return;
        }
        
        scheduleSentinelDNSCheck();
    }

    private void scheduleSentinelDNSCheck() {
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                List<RedisClient> sentinels = new ArrayList<RedisClient>(SentinelConnectionManager.this.sentinels.values());
                
                final AtomicInteger sentinelsCounter = new AtomicInteger(sentinels.size());
                FutureListener<List<InetSocketAddress>> commonListener = new FutureListener<List<InetSocketAddress>>() {
                    @Override
                    public void operationComplete(Future<List<InetSocketAddress>> future) throws Exception {
                        if (sentinelsCounter.decrementAndGet() == 0) {
                            scheduleSentinelDNSCheck();
                        }
                    }
                };
                
                for (final RedisClient client : sentinels) {
                    Future<List<InetSocketAddress>> allNodes = sentinelResolver.resolveAll(InetSocketAddress.createUnresolved(client.getAddr().getHostName(), client.getAddr().getPort()));
                    allNodes.addListener(new FutureListener<List<InetSocketAddress>>() {
                        @Override
                        public void operationComplete(Future<List<InetSocketAddress>> future) throws Exception {
                            if (!future.isSuccess()) {
                                log.error("Unable to resolve " + client.getAddr().getHostName(), future.cause());
                                return;
                            }
                            
                            boolean clientFound = false;
                            for (InetSocketAddress addr : future.getNow()) {
                                boolean found = false;
                                for (RedisClient currentSentinel : SentinelConnectionManager.this.sentinels.values()) {
                                    if (currentSentinel.getAddr().getAddress().getHostAddress().equals(addr.getAddress().getHostAddress())
                                            && currentSentinel.getAddr().getPort() == addr.getPort()) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    URI uri = convert(addr.getAddress().getHostAddress(), "" + addr.getPort());
                                    registerSentinel(uri, getConfig());
                                }
                                if (client.getAddr().getAddress().getHostAddress().equals(addr.getAddress().getHostAddress())
                                        && client.getAddr().getPort() == addr.getPort()) {
                                    clientFound = true;
                                }
                            }
                            if (!clientFound) {
                                String addr = client.getAddr().getAddress().getHostAddress() + ":" + client.getAddr().getPort();
                                RedisClient sentinel = SentinelConnectionManager.this.sentinels.remove(addr);
                                if (sentinel != null) {
                                    sentinel.shutdownAsync();
                                    log.warn("sentinel: {} has down", addr);
                                }
                            }
                        }
                    });
                    allNodes.addListener(commonListener);
                }
            }
        }, config.getDnsMonitoringInterval(), TimeUnit.MILLISECONDS);
    }
    
    private void scheduleChangeCheck(final SentinelServersConfig cfg, final Iterator<RedisClient> iterator) {
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                AtomicReference<Throwable> lastException = new AtomicReference<Throwable>();
                Iterator<RedisClient> iter = iterator;
                if (iter == null) {
                    iter = sentinels.values().iterator();
                }
                checkState(cfg, iter, lastException);
            }
        }, cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private void checkState(final SentinelServersConfig cfg, final Iterator<RedisClient> iterator, final AtomicReference<Throwable> lastException) {
        if (!iterator.hasNext()) {
            if (lastException.get() != null) {
                log.error("Can't update cluster state", lastException.get());
            }
            scheduleChangeCheck(cfg, null);
            return;
        }
        if (!getShutdownLatch().acquire()) {
            return;
        }

        RedisClient client = iterator.next();
        RFuture<RedisConnection> connectionFuture = connectToNode(null, null, client, null);
        connectionFuture.addListener(new FutureListener<RedisConnection>() {
            @Override
            public void operationComplete(Future<RedisConnection> future) throws Exception {
                if (!future.isSuccess()) {
                    lastException.set(future.cause());
                    getShutdownLatch().release();
                    checkState(cfg, iterator, lastException);
                    return;
                }

                RedisConnection connection = future.getNow();
                updateState(cfg, connection, iterator);
            }
        });

    }

    private void updateState(final SentinelServersConfig cfg, final RedisConnection connection, final Iterator<RedisClient> iterator) {
        final AtomicInteger commands = new AtomicInteger(2);
        FutureListener<Object> commonListener = new FutureListener<Object>() {
            
            private final AtomicBoolean failed = new AtomicBoolean();
            
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (commands.decrementAndGet() == 0) {
                    getShutdownLatch().release();
                    if (failed.get()) {
                        scheduleChangeCheck(cfg, iterator);
                    } else {
                        scheduleChangeCheck(cfg, null);
                    }
                }
                if (!future.isSuccess() && failed.compareAndSet(false, true)) {
                    log.error("Can't execute SENTINEL commands on " + connection.getRedisClient().getAddr(), future.cause());
                    closeNodeConnection(connection);
                }
            }
        };
        
        RFuture<List<String>> masterFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_GET_MASTER_ADDR_BY_NAME, cfg.getMasterName());
        masterFuture.addListener(new FutureListener<List<String>>() {
            @Override
            public void operationComplete(Future<List<String>> future) throws Exception {
                if (!future.isSuccess()) {
                    return;
                }

                List<String> master = future.getNow();

                String current = currentMaster.get();
                String newMaster = createAddress(master.get(0), master.get(1));
                if (!newMaster.equals(current)
                        && currentMaster.compareAndSet(current, newMaster)) {
                    changeMaster(singleSlotRange.getStartSlot(), URIBuilder.create(newMaster));
                }
            }
        });
        masterFuture.addListener(commonListener);
        
        if (!config.checkSkipSlavesInit()) {
            RFuture<List<Map<String, String>>> slavesFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_SLAVES, cfg.getMasterName());
            commands.incrementAndGet();
            slavesFuture.addListener(new FutureListener<List<Map<String, String>>>() {
                @Override
                public void operationComplete(Future<List<Map<String, String>>> future) throws Exception {
                    if (!future.isSuccess()) {
                        return;
                    }
                    
                    List<Map<String, String>> slavesMap = future.getNow();
                    final Set<String> currentSlaves = new HashSet<String>(slavesMap.size());
                    List<RFuture<Void>> futures = new ArrayList<RFuture<Void>>();
                    for (Map<String, String> map : slavesMap) {
                        if (map.isEmpty()) {
                            continue;
                        }
                        
                        String ip = map.get("ip");
                        String port = map.get("port");
                        String flags = map.get("flags");
                        String masterHost = map.get("master-host");
                        String masterPort = map.get("master-port");
                        
                        if (flags.contains("s_down") || flags.contains("disconnected")) {
                            slaveDown(ip, port);
                            continue;
                        }
                        if (masterHost.equals("?") || !isUseSameMaster(ip, port, masterHost, masterPort)) {
                            continue;
                        }
                        
                        String slaveAddr = createAddress(ip, port);
                        currentSlaves.add(slaveAddr);
                        RFuture<Void> slaveFuture = addSlave(ip, port, slaveAddr);
                        futures.add(slaveFuture);
                    }
                    
                    CountableListener<Void> listener = new CountableListener<Void>() {
                        @Override
                        protected void onSuccess(Void value) {
                            Set<String> removedSlaves = new HashSet<String>(slaves);
                            removedSlaves.removeAll(currentSlaves);
                            for (String slave : removedSlaves) {
                                String[] parts = slave.replace("redis://", "").split(":");
                                slaveDown(parts[0], parts[1]);
                            }
                        };
                    };
                    
                    listener.setCounter(futures.size());
                    for (RFuture<Void> f : futures) {
                        f.addListener(listener);
                    }
                    
                }
            });
            slavesFuture.addListener(commonListener);
        }
                
        RFuture<List<Map<String, String>>> sentinelsFuture = connection.async(StringCodec.INSTANCE, RedisCommands.SENTINEL_SENTINELS, cfg.getMasterName());
        sentinelsFuture.addListener(new FutureListener<List<Map<String, String>>>() {
            @Override
            public void operationComplete(Future<List<Map<String, String>>> future) throws Exception {
                if (!future.isSuccess()) {
                    return;
                }
                
                List<Map<String, String>> list = future.getNow();
                for (Map<String, String> map : list) {
                    if (map.isEmpty()) {
                        continue;
                    }
                    
                    String ip = map.get("ip");
                    String port = map.get("port");
                    
                    URI sentinelAddr = convert(ip, port);
                    registerSentinel(sentinelAddr, getConfig());
                }
            }
        });
        sentinelsFuture.addListener(commonListener);
    }

    private String createAddress(String host, Object port) {
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return "redis://" + host + ":" + port;
    }

    @Override
    protected MasterSlaveEntry createMasterSlaveEntry(MasterSlaveServersConfig config,
            HashSet<ClusterSlotRange> slots) {
        MasterSlaveEntry entry = new MasterSlaveEntry(slots, this, config);
        List<RFuture<Void>> fs = entry.initSlaveBalancer(disconnectedSlaves);
        for (RFuture<Void> future : fs) {
            future.syncUninterruptibly();
        }
        return entry;
    }

    private RFuture<Void> registerSentinel(final URI addr, final MasterSlaveServersConfig c) {
        String key = addr.getHost() + ":" + addr.getPort();
        RedisClient client = sentinels.get(key);
        if (client != null) {
            return RedissonPromise.newSucceededFuture(null);
        }
        
        client = createClient(NodeType.SENTINEL, addr, c.getConnectTimeout(), c.getRetryInterval() * c.getRetryAttempts(), null);
        sentinels.putIfAbsent(key, client);
        return RedissonPromise.newSucceededFuture(null);
    }

    private RFuture<Void> addSlave(final String ip, final String port, final String slaveAddr) {
        final RPromise<Void> result = new RedissonPromise<Void>();
        // to avoid addition twice
        final MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
        final URI uri = convert(ip, port);
        if (slaves.add(slaveAddr) && !config.checkSkipSlavesInit()) {
            RFuture<Void> future = entry.addSlave(URIBuilder.create(slaveAddr));
            future.addListener(new FutureListener<Void>() {
                @Override
                public void operationComplete(Future<Void> future) throws Exception {
                    if (!future.isSuccess()) {
                        slaves.remove(slaveAddr);
                        result.tryFailure(future.cause());
                        log.error("Can't add slave: " + slaveAddr, future.cause());
                        return;
                    }

                    if (entry.isSlaveUnfreezed(uri) || entry.slaveUp(uri, FreezeReason.MANAGER)) {
                        String slaveAddr = ip + ":" + port;
                        log.info("slave: {} added", slaveAddr);
                        result.trySuccess(null);
                    }
                }

            });
        } else {
            if (entry.hasSlave(uri)) {
                slaveUp(ip, port);
            }
            result.trySuccess(null);
        }
        return result;
    }

    private URI convert(String ip, String port) {
        String addr = createAddress(ip, port);
        URI uri = URIBuilder.create(addr);
        return uri;
    }
    
    private void slaveDown(String ip, String port) {
        if (config.checkSkipSlavesInit()) {
            log.warn("slave: {}:{} has down", ip, port);
        } else {
            MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
            URI uri = convert(ip, port);
            if (entry.slaveDown(uri, FreezeReason.MANAGER)) {
                log.warn("slave: {}:{} has down", ip, port);
            }
        }
    }

    private boolean isUseSameMaster(String slaveIp, String slavePort, String slaveMasterHost, String slaveMasterPort) {
        String master = currentMaster.get();
        String slaveMaster = createAddress(slaveMasterHost, slaveMasterPort);
        if (!master.equals(slaveMaster)) {
            log.warn("Skipped slave up {} for master {} differs from current {}", slaveIp + ":" + slavePort, slaveMaster, master);
            return false;
        }
        return true;
    }
    
    private void slaveUp(String ip, String port) {
        if (config.checkSkipSlavesInit()) {
            String slaveAddr = ip + ":" + port;
            log.info("slave: {} has up", slaveAddr);
            return;
        }

        URI uri = convert(ip, port);
        if (getEntry(singleSlotRange.getStartSlot()).slaveUp(uri, FreezeReason.MANAGER)) {
            String slaveAddr = ip + ":" + port;
            log.info("slave: {} has up", slaveAddr);
        }
    }

    @Override
    protected MasterSlaveServersConfig create(BaseMasterSlaveServersConfig<?> cfg) {
        MasterSlaveServersConfig res = super.create(cfg);
        res.setDatabase(((SentinelServersConfig)cfg).getDatabase());
        return res;
    }

    @Override
    public void shutdown() {
        monitorFuture.cancel(true);
        
        List<RFuture<Void>> futures = new ArrayList<RFuture<Void>>();
        for (RedisClient sentinel : sentinels.values()) {
            RFuture<Void> future = sentinel.shutdownAsync();
            futures.add(future);
        }
        
        for (RFuture<Void> future : futures) {
            future.syncUninterruptibly();
        }
        
        super.shutdown();
    }
}

