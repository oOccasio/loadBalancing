package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashingStrategyTest {
    
    private ConsistentHashingStrategy strategy;
    private List<Server> servers;
    
    @BeforeEach
    void setUp() {
        strategy = new ConsistentHashingStrategy();
        
        servers = Arrays.asList(
            new Server("server-1", "localhost", 5001),
            new Server("server-2", "localhost", 5002),
            new Server("server-3", "localhost", 5003),
            new Server("server-4", "localhost", 5004)
        );
        
        strategy.initialize(servers);
    }
    
    @Test
    void testConsistentMapping() {
        // 같은 클라이언트는 항상 같은 서버로 매핑되어야 함
        String clientId = "user123";
        
        Server firstSelection = strategy.selectServer(servers, clientId);
        strategy.updateServerMetrics(firstSelection, 100L, true);
        
        // 10번 반복해서 같은 서버가 선택되는지 확인
        for (int i = 0; i < 10; i++) {
            Server selection = strategy.selectServer(servers, clientId);
            assertEquals(firstSelection.getId(), selection.getId(), 
                    "같은 클라이언트는 항상 같은 서버로 매핑되어야 합니다.");
            strategy.updateServerMetrics(selection, 100L, true);
        }
    }
    
    @Test
    void testDifferentClientsDistribution() {
        // 다양한 클라이언트들이 여러 서버에 분산되는지 확인
        Map<String, Integer> serverCounts = new HashMap<>();
        
        for (int i = 0; i < 100; i++) {
            String clientId = "user" + i;
            Server selected = strategy.selectServer(servers, clientId);
            serverCounts.merge(selected.getId(), 1, Integer::sum);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        System.out.println("클라이언트 분산 결과: " + serverCounts);
        
        // 모든 서버가 최소 1개의 클라이언트를 받아야 함
        assertEquals(4, serverCounts.size(), "모든 서버가 클라이언트를 받아야 합니다.");
        
        // 어느 서버도 50% 이상의 클라이언트를 받지 않아야 함 (분산성 확인)
        serverCounts.values().forEach(count -> 
                assertTrue(count < 50, "특정 서버에 너무 많은 클라이언트가 집중되면 안됩니다."));
    }
    
    @Test
    void testVirtualNodesDistribution() {
        // 가상 노드가 균등하게 분배되는지 확인
        Map<String, Integer> ringDistribution = strategy.getRingDistribution();
        System.out.println("해시 링 분배: " + ringDistribution);
        
        // 모든 서버가 동일한 가상 노드 수를 가져야 함
        int expectedVirtualNodes = 150; // VIRTUAL_NODES 상수값
        ringDistribution.values().forEach(count -> 
                assertEquals(expectedVirtualNodes, count, 
                        "모든 서버는 동일한 수의 가상 노드를 가져야 합니다."));
        
        // 총 가상 노드 수 확인
        int totalVirtualNodes = ringDistribution.values().stream()
                .mapToInt(Integer::intValue).sum();
        assertEquals(expectedVirtualNodes * servers.size(), totalVirtualNodes);
    }
    
    @Test
    void testServerAddition() {
        // 초기 상태에서 클라이언트 매핑 확인
        Map<String, String> initialMappings = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String clientId = "client" + i;
            Server server = strategy.predictServer(servers, clientId);
            initialMappings.put(clientId, server.getId());
        }
        
        // 새 서버 추가
        Server newServer = new Server("server-5", "localhost", 5005);
        List<Server> expandedServers = Arrays.asList(
            servers.get(0), servers.get(1), servers.get(2), servers.get(3), newServer
        );
        
        strategy.initialize(expandedServers);
        
        // 서버 추가 후 매핑 변화 확인
        int changedMappings = 0;
        for (int i = 0; i < 20; i++) {
            String clientId = "client" + i;
            Server server = strategy.predictServer(expandedServers, clientId);
            
            if (!server.getId().equals(initialMappings.get(clientId))) {
                changedMappings++;
            }
        }
        
        System.out.printf("서버 추가 후 변경된 매핑: %d/20 (%.1f%%)%n", 
                changedMappings, (changedMappings / 20.0) * 100);
        
        // 변경된 매핑이 50% 미만이어야 함 (일관성 해싱의 장점)
        assertTrue(changedMappings < 10, 
                "서버 추가 시 변경되는 매핑이 50% 미만이어야 합니다.");
    }
    
    @Test
    void testServerRemoval() {
        // 초기 매핑 저장
        Map<String, String> initialMappings = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String clientId = "client" + i;
            Server server = strategy.predictServer(servers, clientId);
            initialMappings.put(clientId, server.getId());
        }
        
        // server-2 제거
        List<Server> reducedServers = Arrays.asList(
            servers.get(0), servers.get(2), servers.get(3)
        );
        
        strategy.initialize(reducedServers);
        
        // 서버 제거 후 매핑 변화 확인
        int changedMappings = 0;
        for (int i = 0; i < 20; i++) {
            String clientId = "client" + i;
            Server server = strategy.predictServer(reducedServers, clientId);
            
            String initialServer = initialMappings.get(clientId);
            if (!server.getId().equals(initialServer)) {
                changedMappings++;
                System.out.printf("매핑 변경: %s (%s → %s)%n", 
                        clientId, initialServer, server.getId());
            }
        }
        
        System.out.printf("서버 제거 후 변경된 매핑: %d/20%n", changedMappings);
        
        // server-2에 매핑되었던 클라이언트들만 변경되어야 함
        assertTrue(changedMappings <= 20, "일부 클라이언트의 매핑만 변경되어야 합니다.");
    }
    
    @Test
    void testUnhealthyServerExclusion() {
        // server-3을 비활성화
        servers.get(2).setHealthy(false);
        strategy.initialize(servers);
        
        // 100번 요청해서 server-3이 선택되지 않는지 확인
        for (int i = 0; i < 100; i++) {
            String clientId = "client" + i;
            Server selected = strategy.selectServer(servers, clientId);
            
            assertNotEquals("server-3", selected.getId(), 
                    "비활성 서버는 선택되지 않아야 합니다.");
            
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 해시 링에 server-3의 가상 노드가 없어야 함
        Map<String, Integer> ringDistribution = strategy.getRingDistribution();
        assertFalse(ringDistribution.containsKey("server-3"), 
                "비활성 서버는 해시 링에 없어야 합니다.");
    }
    
    @Test
    void testHashConsistency() {
        // 동일한 입력에 대해 해시값이 일관되는지 확인
        String clientId = "test-client";
        
        long hash1 = strategy.getClientHash(clientId);
        long hash2 = strategy.getClientHash(clientId);
        long hash3 = strategy.getClientHash(clientId);
        
        assertEquals(hash1, hash2, "동일한 입력의 해시값은 일관되어야 합니다.");
        assertEquals(hash2, hash3, "동일한 입력의 해시값은 일관되어야 합니다.");
        
        // 다른 입력에 대해서는 다른 해시값
        long differentHash = strategy.getClientHash("different-client");
        assertNotEquals(hash1, differentHash, "다른 입력은 다른 해시값을 가져야 합니다.");
    }
    
    @Test
    void testEmptyClientInfo() {
        // 빈 클라이언트 정보에 대한 처리
        Server server1 = strategy.selectServer(servers, "");
        Server server2 = strategy.selectServer(servers, null);
        Server server3 = strategy.selectServer(servers, "   ");
        
        assertNotNull(server1, "빈 클라이언트 정보도 서버를 선택해야 합니다.");
        assertNotNull(server2, "null 클라이언트 정보도 서버를 선택해야 합니다.");
        assertNotNull(server3, "공백 클라이언트 정보도 서버를 선택해야 합니다.");
        
        strategy.updateServerMetrics(server1, 100L, true);
        strategy.updateServerMetrics(server2, 100L, true);
        strategy.updateServerMetrics(server3, 100L, true);
    }
    
    @Test
    void testRingSize() {
        // 해시 링 크기 확인
        int expectedSize = servers.size() * 150; // 서버 수 × VIRTUAL_NODES
        assertEquals(expectedSize, strategy.getRingSize(), 
                "해시 링 크기가 예상값과 일치해야 합니다.");
    }
}
