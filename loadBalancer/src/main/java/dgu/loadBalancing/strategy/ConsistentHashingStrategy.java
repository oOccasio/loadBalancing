package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent Hashing 로드밸런싱 전략
 * 해시 링을 사용하여 클라이언트를 서버에 일관되게 매핑하는 알고리즘
 */
@Component("consistentHashing")
public class ConsistentHashingStrategy implements LoadBalancingStrategy {
    
    private static final int VIRTUAL_NODES = 150; // 가상 노드 수 (핫스팟 방지)
    private static final ThreadLocal<MessageDigest> MD5_HOLDER = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    // 2. volatile 참조로 원자적 교체 (Copy-on-Write 패턴)
    private volatile ConcurrentSkipListMap<Long, Server> hashRing = new ConcurrentSkipListMap<>();

    // 3. 초기화 동기화를 위한 락
    private final ReentrantReadWriteLock ringLock = new ReentrantReadWriteLock();
    private volatile boolean ringInitialized = false;


    @Override
    public void initialize(List<Server> servers) {
        buildHashRing(servers);
    }

    public Server selectServer(List<Server> servers, String clientInfo) {

        // fast-path (락 없음)
        if (!ringInitialized || needsRebuild(servers)) {
            rebuildIfNeeded(servers);
        }

        ConcurrentSkipListMap<Long, Server> currentRing = this.hashRing;
        long clientHash = hash(clientInfo);

        Map.Entry<Long, Server> entry = currentRing.ceilingEntry(clientHash);
        if (entry == null) entry = currentRing.firstEntry();

        return entry.getValue();
    }

    private synchronized void rebuildIfNeeded(List<Server> servers) {
        // slow-path: 동기화
        if (!ringInitialized || needsRebuild(servers)) {
            buildHashRing(servers);
        }
    }

    @Override
    public void updateServerMetrics(Server server, long responseTime, boolean success) {
        server.decrementConnections();
        
        if (success) {
            server.recordResponseTime(responseTime);
        }
    }
    
    @Override
    public String getStrategyName() {
        return "Consistent Hashing";
    }
    
    @Override
    public String getDescription() {
        return "Consistent Hashing - 해시 링을 사용하여 클라이언트를 서버에 일관되게 매핑";
    }
    
    @Override
    public void onServerAdded(Server server) {
        if (server.isHealthy()) {
            addServerToRing(server);
        }
    }
    
    @Override
    public void onServerRemoved(Server server) {
        removeServerFromRing(server);
    }
    
    /**
     * 해시 링 구성
     */
    private void buildHashRing(List<Server> servers) {
        // 새 링을 별도로 구성 (기존 링은 그대로 서비스 중)
        ConcurrentSkipListMap<Long, Server> newRing = new ConcurrentSkipListMap<>();

        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();

        for (Server server : healthyServers) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                String virtualNodeKey = server.getId() + "#" + i;
                long hash = hash(virtualNodeKey);
                newRing.put(hash, server);
            }
        }

        // 원자적으로 교체 (Copy-on-Write)
        this.hashRing = newRing;
        this.ringInitialized = true;
    }


    /**
     * 서버를 해시 링에 추가 (가상 노드 포함)
     */
    private void addServerToRing(Server server) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeKey = server.getId() + "#" + i;
            long hash = hash(virtualNodeKey);
            hashRing.put(hash, server);
        }
    }
    
    /**
     * 서버를 해시 링에서 제거
     */
    private void removeServerFromRing(Server server) {
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeKey = server.getId() + "#" + i;
            long hash = hash(virtualNodeKey);
            hashRing.remove(hash);
        }
    }
    
    /**
     * 해시 링 재구성이 필요한지 확인
     */
    private boolean needsRebuild(List<Server> servers) {
        Set<Server> healthyServers = new HashSet<>(servers.stream()
                .filter(Server::isHealthy)
                .toList());
        
        Set<Server> ringServers = new HashSet<>(hashRing.values());
        
        return !healthyServers.equals(ringServers);
    }
    
    /**
     * 문자열의 MD5 해시값을 long으로 변환
     */
    private long hash(String input) {
        // ThreadLocal이라 동기화 불필요
        MessageDigest md5 = MD5_HOLDER.get();
        md5.reset();
        md5.update(input.getBytes());
        byte[] digest = md5.digest();

        long hash = 0;
        for (int i = 0; i < 8; i++) {
            hash = (hash << 8) | (digest[i] & 0xFF);
        }
        return Math.abs(hash);
    }
    
    /**
     * 클라이언트가 매핑될 서버 예측 (실제 연결 증가 없이)
     */
    public Server predictServer(List<Server> servers, String clientInfo) {
        if (!ringInitialized || needsRebuild(servers)) {
            buildHashRing(servers);
        }
        
        if (hashRing.isEmpty()) {
            return null;
        }
        
        long clientHash = hash(clientInfo);
        Map.Entry<Long, Server> entry = hashRing.ceilingEntry(clientHash);
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        
        return entry.getValue();
    }
    
    /**
     * 해시 링의 현재 상태 반환 (디버깅용)
     */
    public Map<String, Integer> getRingDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        
        for (Server server : hashRing.values()) {
            distribution.merge(server.getId(), 1, Integer::sum);
        }
        
        return distribution;
    }
    
    /**
     * 해시 링 크기 반환 (테스트용)
     */
    public int getRingSize() {
        return hashRing.size();
    }
    
    /**
     * 클라이언트 해시값 반환 (테스트용)
     */
    public long getClientHash(String clientInfo) {
        return hash(clientInfo);
    }
    
    /**
     * 해시 링 초기화 (테스트용)
     */
    public void clearRing() {
        hashRing.clear();
        ringInitialized = false;
    }
}
