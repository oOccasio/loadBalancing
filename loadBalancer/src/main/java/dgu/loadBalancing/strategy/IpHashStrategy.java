package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * IP Hash 로드밸런싱 전략
 * 클라이언트 IP 주소의 해시값을 사용하여 서버를 선택하는 알고리즘
 */
@Component("ipHash")
public class IpHashStrategy implements LoadBalancingStrategy {
    
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    
    // 클라이언트 IP별 서버 매핑 캐시 (세션 지속성)
    private final Map<String, String> ipServerMapping = new ConcurrentHashMap<>();
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        // 건강한 서버만 필터링
        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();
        
        if (healthyServers.isEmpty()) {
            throw new IllegalStateException("사용 가능한 건강한 서버가 없습니다.");
        }
        
        String clientIp = extractIpFromClientInfo(clientInfo);
        
        // 캐시된 매핑이 있고 해당 서버가 여전히 건강한지 확인
        String cachedServerId = ipServerMapping.get(clientIp);
        if (cachedServerId != null) {
            Server cachedServer = healthyServers.stream()
                    .filter(server -> server.getId().equals(cachedServerId))
                    .findFirst()
                    .orElse(null);
            
            if (cachedServer != null) {
                cachedServer.incrementConnections();
                System.out.printf("[IP Hash] 캐시된 매핑 사용: %s → %s%n", clientIp, cachedServerId);
                return cachedServer;
            } else {
                // 캐시된 서버가 더 이상 사용 불가능하면 제거
                ipServerMapping.remove(clientIp);
            }
        }
        
        // IP 해시 기반 서버 선택
        int hash = calculateHash(clientIp);
        int serverIndex = Math.abs(hash) % healthyServers.size();
        Server selectedServer = healthyServers.get(serverIndex);
        
        // 매핑 캐시에 저장
        ipServerMapping.put(clientIp, selectedServer.getId());
        
        selectedServer.incrementConnections();
        
        System.out.printf("[IP Hash] 새 매핑: %s (hash: %d) → %s (index: %d)%n", 
                clientIp, hash, selectedServer.getId(), serverIndex);
        
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
        return "IP Hash";
    }
    
    @Override
    public String getDescription() {
        return "IP Hash - 클라이언트 IP 주소의 해시값을 사용하여 서버를 선택";
    }
    
    @Override
    public void onServerRemoved(Server server) {
        // 제거된 서버에 매핑된 IP들을 캐시에서 제거
        ipServerMapping.entrySet().removeIf(entry -> 
                entry.getValue().equals(server.getId()));
        
        System.out.printf("[IP Hash] 서버 제거로 인한 매핑 정리: %s%n", server.getId());
    }
    
    /**
     * 클라이언트 정보에서 IP 주소 추출
     */
    private String extractIpFromClientInfo(String clientInfo) {
        if (clientInfo == null || clientInfo.trim().isEmpty()) {
            // 클라이언트 정보가 없으면 기본값 사용
            return "127.0.0.1";
        }
        
        // 클라이언트 정보가 IP 형태인지 확인
        if (IP_PATTERN.matcher(clientInfo.trim()).matches()) {
            return clientInfo.trim();
        }
        
        // IP가 아니면 문자열 해시를 IP 형태로 변환
        int hash = clientInfo.hashCode();
        int a = (Math.abs(hash) % 255) + 1;        // 1-255
        int b = (Math.abs(hash >> 8) % 255) + 1;   // 1-255  
        int c = (Math.abs(hash >> 16) % 255) + 1;  // 1-255
        int d = (Math.abs(hash >> 24) % 255) + 1;  // 1-255
        
        return String.format("%d.%d.%d.%d", a, b, c, d);
    }
    
    /**
     * IP 주소의 해시값 계산
     */
    private int calculateHash(String ip) {
        // IP 주소의 각 옥텟을 가중치를 두어 해시 계산
        String[] octets = ip.split("\\.");
        int hash = 0;
        
        for (int i = 0; i < octets.length; i++) {
            int octet = Integer.parseInt(octets[i]);
            hash = hash * 256 + octet;  // 각 옥텟에 가중치 적용
        }
        
        return hash;
    }
    
    /**
     * 특정 IP가 어느 서버에 매핑되는지 예측 (실제 연결 증가 없이)
     */
    public Server predictServerForIp(List<Server> servers, String ip) {
        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();
        
        if (healthyServers.isEmpty()) {
            return null;
        }
        
        String normalizedIp = extractIpFromClientInfo(ip);
        int hash = calculateHash(normalizedIp);
        int serverIndex = Math.abs(hash) % healthyServers.size();
        
        return healthyServers.get(serverIndex);
    }
    
    /**
     * 현재 IP-서버 매핑 상태 반환 (모니터링용)
     */
    public Map<String, String> getCurrentMappings() {
        return Map.copyOf(ipServerMapping);
    }
    
    /**
     * 특정 서버에 매핑된 IP 개수 반환
     */
    public long getIpCountForServer(String serverId) {
        return ipServerMapping.values().stream()
                .filter(serverIdInMap -> serverIdInMap.equals(serverId))
                .count();
    }
    
    /**
     * 매핑 캐시 크기 반환 (테스트용)
     */
    public int getMappingCacheSize() {
        return ipServerMapping.size();
    }
    
    /**
     * 매핑 캐시 초기화 (테스트용)
     */
    public void clearMappingCache() {
        ipServerMapping.clear();
    }
    
    /**
     * IP 주소 형식 검증
     */
    public boolean isValidIp(String ip) {
        return ip != null && IP_PATTERN.matcher(ip.trim()).matches();
    }
    
    /**
     * IP 해시값 반환 (테스트용)
     */
    public int getIpHash(String ip) {
        String normalizedIp = extractIpFromClientInfo(ip);
        return calculateHash(normalizedIp);
    }
}
