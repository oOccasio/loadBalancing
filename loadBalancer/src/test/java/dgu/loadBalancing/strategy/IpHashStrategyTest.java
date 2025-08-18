package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IpHashStrategyTest {
    
    private IpHashStrategy strategy;
    private List<Server> servers;
    
    @BeforeEach
    void setUp() {
        strategy = new IpHashStrategy();
        
        servers = Arrays.asList(
            new Server("server-1", "localhost", 5001),
            new Server("server-2", "localhost", 5002),
            new Server("server-3", "localhost", 5003),
            new Server("server-4", "localhost", 5004)
        );
        
        strategy.clearMappingCache();
    }
    
    @Test
    void testSameIpMapsToSameServer() {
        // 같은 IP는 항상 같은 서버로 매핑되어야 함
        String clientIp = "192.168.1.100";
        
        Server firstSelection = strategy.selectServer(servers, clientIp);
        strategy.updateServerMetrics(firstSelection, 100L, true);
        
        // 10번 반복해서 같은 서버가 선택되는지 확인
        for (int i = 0; i < 10; i++) {
            Server selection = strategy.selectServer(servers, clientIp);
            assertEquals(firstSelection.getId(), selection.getId(), 
                    "같은 IP는 항상 같은 서버로 매핑되어야 합니다.");
            strategy.updateServerMetrics(selection, 100L, true);
        }
    }
    
    @Test
    void testDifferentIpsDistribution() {
        // 다양한 IP들이 여러 서버에 분산되는지 확인
        Map<String, Integer> serverCounts = new HashMap<>();
        
        // 다양한 IP 대역에서 요청
        String[] ipAddresses = {
            "192.168.1.10", "192.168.1.20", "192.168.1.30", "192.168.1.40",
            "10.0.0.10", "10.0.0.20", "10.0.0.30", "10.0.0.40",
            "172.16.1.10", "172.16.1.20", "172.16.1.30", "172.16.1.40",
            "203.123.45.10", "203.123.45.20", "203.123.45.30", "203.123.45.40"
        };
        
        for (String ip : ipAddresses) {
            Server selected = strategy.selectServer(servers, ip);
            serverCounts.merge(selected.getId(), 1, Integer::sum);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        System.out.println("IP 분산 결과: " + serverCounts);
        
        // 모든 서버가 최소 1개의 IP를 받아야 함
        assertTrue(serverCounts.size() >= 2, "여러 서버에 IP가 분산되어야 합니다.");
        
        // 어느 서버도 75% 이상의 IP를 받지 않아야 함
        int totalIps = ipAddresses.length;
        serverCounts.values().forEach(count -> 
                assertTrue(count < totalIps * 0.75, 
                        "특정 서버에 너무 많은 IP가 집중되면 안됩니다."));
    }
    
    @Test
    void testIpValidation() {
        // 유효한 IP 형식들
        assertTrue(strategy.isValidIp("192.168.1.1"), "유효한 IP는 검증을 통과해야 합니다.");
        assertTrue(strategy.isValidIp("10.0.0.1"), "유효한 IP는 검증을 통과해야 합니다.");
        assertTrue(strategy.isValidIp("255.255.255.255"), "유효한 IP는 검증을 통과해야 합니다.");
        assertTrue(strategy.isValidIp("0.0.0.0"), "유효한 IP는 검증을 통과해야 합니다.");
        
        // 무효한 IP 형식들
        assertFalse(strategy.isValidIp("256.1.1.1"), "잘못된 IP는 검증을 실패해야 합니다.");
        assertFalse(strategy.isValidIp("192.168.1"), "잘못된 IP는 검증을 실패해야 합니다.");
        assertFalse(strategy.isValidIp("192.168.1.1.1"), "잘못된 IP는 검증을 실패해야 합니다.");
        assertFalse(strategy.isValidIp("not-an-ip"), "잘못된 IP는 검증을 실패해야 합니다.");
        assertFalse(strategy.isValidIp(""), "빈 문자열은 검증을 실패해야 합니다.");
        assertFalse(strategy.isValidIp(null), "null은 검증을 실패해야 합니다.");
    }
    
    @Test
    void testNonIpClientInfoHandling() {
        // IP가 아닌 클라이언트 정보 처리
        String[] nonIpInputs = {
            "user123", "session-abc-def", "client-id-456", 
            "", null, "   ", "very-long-client-identifier-string"
        };
        
        Map<String, String> clientServerMappings = new HashMap<>();
        
        for (String clientInfo : nonIpInputs) {
            Server selected = strategy.selectServer(servers, clientInfo);
            String key = clientInfo == null ? "null" : clientInfo;
            clientServerMappings.put(key, selected.getId());
            
            assertNotNull(selected, "모든 클라이언트 정보에 대해 서버가 선택되어야 합니다.");
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 같은 클라이언트 정보로 다시 요청했을 때 같은 서버 선택되는지 확인
        for (String clientInfo : nonIpInputs) {
            Server selected = strategy.selectServer(servers, clientInfo);
            String key = clientInfo == null ? "null" : clientInfo;
            assertEquals(clientServerMappings.get(key), selected.getId(), 
                    "같은 클라이언트 정보는 같은 서버로 매핑되어야 합니다.");
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        System.out.println("비IP 클라이언트 매핑: " + clientServerMappings);
    }
    
    @Test
    void testServerRemovalMapping() {
        // 초기 매핑 생성
        String[] testIps = {"192.168.1.10", "192.168.1.20", "192.168.1.30", "192.168.1.40"};
        
        for (String ip : testIps) {
            Server selected = strategy.selectServer(servers, ip);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        int initialMappingSize = strategy.getMappingCacheSize();
        assertTrue(initialMappingSize > 0, "초기 매핑이 생성되어야 합니다.");
        
        // server-2 제거 시뮬레이션
        Server removedServer = servers.get(1); // server-2
        strategy.onServerRemoved(removedServer);
        
        // server-2에 매핑된 IP들이 캐시에서 제거되었는지 확인
        Map<String, String> currentMappings = strategy.getCurrentMappings();
        assertFalse(currentMappings.containsValue("server-2"), 
                "제거된 서버에 대한 매핑이 캐시에서 제거되어야 합니다.");
        
        System.out.println("서버 제거 후 매핑: " + currentMappings);
    }
    
    @Test
    void testUnhealthyServerExclusion() {
        // server-3을 비활성화
        servers.get(2).setHealthy(false);
        
        String testIp = "192.168.1.100";
        
        // 여러 번 요청해서 비활성 서버가 선택되지 않는지 확인
        for (int i = 0; i < 10; i++) {
            Server selected = strategy.selectServer(servers, testIp + i);
            assertNotEquals("server-3", selected.getId(), 
                    "비활성 서버는 선택되지 않아야 합니다.");
            strategy.updateServerMetrics(selected, 100L, true);
        }
    }
    
    @Test
    void testCachedMappingConsistency() {
        String testIp = "192.168.1.100";
        
        // 첫 번째 요청으로 매핑 생성
        Server firstServer = strategy.selectServer(servers, testIp);
        strategy.updateServerMetrics(firstServer, 100L, true);
        
        assertEquals(1, strategy.getMappingCacheSize(), "매핑 캐시에 항목이 추가되어야 합니다.");
        
        // 두 번째 요청에서 캐시된 매핑 사용
        Server secondServer = strategy.selectServer(servers, testIp);
        assertEquals(firstServer.getId(), secondServer.getId(), 
                "캐시된 매핑이 사용되어야 합니다.");
        
        strategy.updateServerMetrics(secondServer, 100L, true);
        
        // 캐시 크기는 그대로여야 함
        assertEquals(1, strategy.getMappingCacheSize(), 
                "동일 IP 재요청 시 캐시 크기는 변경되지 않아야 합니다.");
    }
    
    @Test
    void testHashConsistency() {
        // 동일한 IP에 대해 해시값이 일관되는지 확인
        String testIp = "192.168.1.100";
        
        int hash1 = strategy.getIpHash(testIp);
        int hash2 = strategy.getIpHash(testIp);
        int hash3 = strategy.getIpHash(testIp);
        
        assertEquals(hash1, hash2, "동일한 IP의 해시값은 일관되어야 합니다.");
        assertEquals(hash2, hash3, "동일한 IP의 해시값은 일관되어야 합니다.");
        
        // 다른 IP에 대해서는 다른 해시값 (대부분의 경우)
        String differentIp = "10.0.0.1";
        int differentHash = strategy.getIpHash(differentIp);
        
        // 해시 충돌 가능성은 있지만 일반적으로는 다른 값
        System.out.printf("Hash values - %s: %d, %s: %d%n", 
                testIp, hash1, differentIp, differentHash);
    }
    
    @Test
    void testPredictServerForIp() {
        // 실제 연결 없이 IP 매핑 예측
        String testIp = "192.168.1.100";
        
        Server predictedServer = strategy.predictServerForIp(servers, testIp);
        assertNotNull(predictedServer, "예측 서버가 반환되어야 합니다.");
        
        // 실제 선택과 예측이 일치하는지 확인
        Server actualServer = strategy.selectServer(servers, testIp);
        assertEquals(predictedServer.getId(), actualServer.getId(), 
                "예측 서버와 실제 선택 서버가 일치해야 합니다.");
        
        strategy.updateServerMetrics(actualServer, 100L, true);
    }
    
    @Test
    void testMappingStatistics() {
        // 여러 IP에 대한 매핑 통계
        String[] testIps = {
            "192.168.1.10", "192.168.1.11", "192.168.1.12",
            "10.0.0.10", "10.0.0.11", "172.16.1.10"
        };
        
        for (String ip : testIps) {
            Server selected = strategy.selectServer(servers, ip);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 각 서버별 매핑된 IP 개수 확인
        for (Server server : servers) {
            long ipCount = strategy.getIpCountForServer(server.getId());
            System.out.printf("Server %s: %d IPs mapped%n", server.getId(), ipCount);
        }
        
        assertEquals(testIps.length, strategy.getMappingCacheSize(), 
                "모든 IP에 대한 매핑이 캐시되어야 합니다.");
    }
}
