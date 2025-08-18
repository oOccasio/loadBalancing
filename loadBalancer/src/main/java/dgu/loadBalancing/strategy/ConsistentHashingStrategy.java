package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent Hashing 로드밸런싱 전략
 * 해시 링을 사용하여 클라이언트를 서버에 일관되게 매핑하는 알고리즘
 */
@Component("consistentHashing")
public class ConsistentHashingStrategy implements LoadBalancingStrategy {
    
    private static final int VIRTUAL_NODES = 150; // 가상 노드 수 (핫스팟 방지)
    private final ConcurrentSkipListMap<Long, Server> hashRing = new ConcurrentSkipListMap<>();
    private final MessageDigest md5;
    private volatile boolean ringInitialized = false;
    
    public ConsistentHashingStrategy() {
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 해시 알고리즘을 찾을 수 없습니다.", e);
        }
    }
    
    @Override
    public void initialize(List<Server> servers) {
        buildHashRing(servers);
    }
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        if (clientInfo == null || clientInfo.trim().isEmpty()) {
            clientInfo = "unknown-client-" + System.currentTimeMillis();
        }
        
        // 해시 링이 초기화되지 않았거나 서버 목록이 변경된 경우 재구성
        if (!ringInitialized || needsRebuild(servers)) {
            buildHashRing(servers);
        }
        
        if (hashRing.isEmpty()) {
            throw new IllegalStateException("사용 가능한 건강한 서버가 없습니다.");
        }
        
        // 클라이언트 정보의 해시값 계산
        long clientHash = hash(clientInfo);
        
        // 해시 링에서 시계방향으로 가장 가까운 서버 찾기
        Map.Entry<Long, Server> entry = hashRing.ceilingEntry(clientHash);
        if (entry == null) {
            // 링의 끝에 도달하면 처음부터 시작
            entry = hashRing.firstEntry();
        }
        
        Server selectedServer = entry.getValue();
        selectedServer.incrementConnections();
        
        System.out.printf("[Consistent Hashing] 클라이언트: %s (hash: %d) → 서버: %s%n", 
                clientInfo, clientHash, selectedServer.getId());
        
        return selectedServer;
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
        hashRing.clear();
        
        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();
        
        for (Server server : healthyServers) {
            addServerToRing(server);
        }
        
        ringInitialized = true;
        
        System.out.printf("[Consistent Hashing] 해시 링 구성 완료: %d개 서버, %d개 가상 노드%n", 
                healthyServers.size(), hashRing.size());
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
        synchronized (md5) {
            md5.reset();
            md5.update(input.getBytes());
            byte[] digest = md5.digest();
            
            // 바이트 배열을 long으로 변환 (8바이트만 사용)
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            
            return Math.abs(hash); // 양수로 변환
        }
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
