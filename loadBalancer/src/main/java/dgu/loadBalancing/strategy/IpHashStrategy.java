package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * IP Hash 로드밸런싱 전략
 * 클라이언트 IP 주소의 해시값을 사용하여 서버를 선택하는 알고리즘
 *
 * 동시성 처리:
 * - ConcurrentHashMap.compute()로 check-then-act race condition 방지
 * - 캐시 조회 + 유효성 검증 + 갱신을 원자적으로 처리
 */
@Component("ipHash")
public class IpHashStrategy implements LoadBalancingStrategy {

    private static final Logger log = LoggerFactory.getLogger(IpHashStrategy.class);

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

        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();

        if (healthyServers.isEmpty()) {
            throw new IllegalStateException("사용 가능한 건강한 서버가 없습니다.");
        }

        String clientIp = extractIpFromClientInfo(clientInfo);

        // compute()로 캐시 확인 + 서버 선택 + 저장을 원자적으로 처리
        String selectedServerId = ipServerMapping.compute(clientIp, (ip, cachedServerId) -> {
            // 캐시된 서버가 있고 여전히 건강한지 확인
            if (cachedServerId != null) {
                boolean serverStillHealthy = healthyServers.stream()
                        .anyMatch(server -> server.getId().equals(cachedServerId));

                if (serverStillHealthy) {
                    log.debug("[IP Hash] 캐시된 매핑 사용: {} → {}", ip, cachedServerId);
                    return cachedServerId;
                }
                // 캐시된 서버가 더 이상 건강하지 않으면 새로 선택
                log.debug("[IP Hash] 캐시된 서버 {} 가 건강하지 않아 재선택", cachedServerId);
            }

            // 새 서버 선택
            int hash = calculateHash(ip);
            int serverIndex = Math.abs(hash) % healthyServers.size();
            String newServerId = healthyServers.get(serverIndex).getId();

            log.debug("[IP Hash] 새 매핑: {} (hash: {}) → {} (index: {})",
                    ip, hash, newServerId, serverIndex);

            return newServerId;
        });

        // compute() 이후 서버 객체 조회 - 이 시점에 상태가 변했을 수 있으므로 방어적 처리
        Server selectedServer = healthyServers.stream()
                .filter(server -> server.getId().equals(selectedServerId))
                .findFirst()
                .orElseGet(() -> {
                    // 극히 드문 케이스: compute와 여기 사이에 서버 상태가 변경됨
                    // 캐시 무효화하고 첫 번째 건강한 서버로 폴백
                    log.warn("[IP Hash] 선택된 서버 {}를 찾을 수 없어 폴백 처리", selectedServerId);
                    ipServerMapping.remove(clientIp);
                    return healthyServers.get(0);
                });

        selectedServer.incrementConnections();
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
        // ConcurrentHashMap의 removeIf는 내부적으로 동기화됨
        ipServerMapping.entrySet().removeIf(entry ->
                entry.getValue().equals(server.getId()));

        log.info("[IP Hash] 서버 제거로 인한 매핑 정리: {}", server.getId());
    }

    /**
     * 클라이언트 정보에서 IP 주소 추출
     */
    private String extractIpFromClientInfo(String clientInfo) {
        if (clientInfo == null || clientInfo.trim().isEmpty()) {
            return "127.0.0.1";
        }

        String trimmed = clientInfo.trim();
        if (IP_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        // IP가 아니면 문자열 해시를 IP 형태로 변환
        int hash = clientInfo.hashCode();
        int a = (Math.abs(hash) % 255) + 1;
        int b = (Math.abs(hash >> 8) % 255) + 1;
        int c = (Math.abs(hash >> 16) % 255) + 1;
        int d = (Math.abs(hash >> 24) % 255) + 1;

        return String.format("%d.%d.%d.%d", a, b, c, d);
    }

    /**
     * IP 주소의 해시값 계산
     */
    private int calculateHash(String ip) {
        String[] octets = ip.split("\\.");
        int hash = 0;

        for (String octet : octets) {
            hash = hash * 256 + Integer.parseInt(octet);
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
                .filter(id -> id.equals(serverId))
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