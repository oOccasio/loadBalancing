package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WeightedRoundRobinStrategyTest {
    
    private WeightedRoundRobinStrategy strategy;
    private List<Server> servers;
    
    @BeforeEach
    void setUp() {
        strategy = new WeightedRoundRobinStrategy();
        
        // 서버별로 다른 가중치 설정
        Server server1 = new Server("server-1", "localhost", 5001);
        server1.setWeight(4); // 40% (4/10)
        
        Server server2 = new Server("server-2", "localhost", 5002);
        server2.setWeight(3); // 30% (3/10)
        
        Server server3 = new Server("server-3", "localhost", 5003);
        server3.setWeight(2); // 20% (2/10)
        
        Server server4 = new Server("server-4", "localhost", 5004);
        server4.setWeight(1); // 10% (1/10)
        
        servers = Arrays.asList(server1, server2, server3, server4);
        
        // 초기화
        strategy.initialize(servers);
    }
    
    @Test
    void testWeightedDistribution() {
        // 100번 요청하여 가중치에 따른 분배 확인
        Map<String, Integer> counts = new HashMap<>();
        int totalRequests = 100;
        
        for (int i = 0; i < totalRequests; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            counts.merge(selected.getId(), 1, Integer::sum);
            
            // 요청 완료 시뮬레이션
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 가중치에 따른 예상 분배율 검증 (오차 ±5% 허용)
        assertEquals(40, counts.get("server-1"), 5, "server-1은 약 40% 요청을 처리해야 합니다.");
        assertEquals(30, counts.get("server-2"), 5, "server-2는 약 30% 요청을 처리해야 합니다.");
        assertEquals(20, counts.get("server-3"), 5, "server-3은 약 20% 요청을 처리해야 합니다.");
        assertEquals(10, counts.get("server-4"), 5, "server-4는 약 10% 요청을 처리해야 합니다.");
        
        System.out.println("실제 분배 결과: " + counts);
    }
    
    @Test
    void testWeightedListSize() {
        // 가중치 합계만큼 리스트 크기가 되는지 확인
        int expectedSize = servers.stream()
                .mapToInt(Server::getWeight)
                .sum(); // 4 + 3 + 2 + 1 = 10
        
        assertEquals(expectedSize, strategy.getWeightedListSize());
    }
    
    @Test
    void testEqualWeights() {
        // 모든 서버 가중치를 동일하게 설정
        servers.forEach(server -> server.setWeight(1));
        strategy.initialize(servers);
        
        Map<String, Integer> counts = new HashMap<>();
        int totalRequests = 80; // 4의 배수로 정확한 분배 확인
        
        for (int i = 0; i < totalRequests; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            counts.merge(selected.getId(), 1, Integer::sum);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 모든 서버가 동일하게 20번씩 선택되어야 함
        counts.values().forEach(count -> 
            assertEquals(20, count, "동일한 가중치일 때는 균등 분배되어야 합니다."));
    }
    
    @Test
    void testUnhealthyServerExclusion() {
        // server-2를 비활성화
        servers.get(1).setHealthy(false);
        strategy.initialize(servers);
        
        Map<String, Integer> counts = new HashMap<>();
        
        for (int i = 0; i < 70; i++) { // 7의 배수 (남은 가중치 합: 4+2+1=7)
            Server selected = strategy.selectServer(servers, "client-" + i);
            counts.merge(selected.getId(), 1, Integer::sum);
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // server-2는 선택되지 않아야 함
        assertNull(counts.get("server-2"), "비활성화된 서버는 선택되지 않아야 합니다.");
        
        // 나머지 서버들은 가중치에 따라 분배
        assertEquals(40, counts.get("server-1"), "server-1: 4/7 * 70 = 40");
        assertEquals(20, counts.get("server-3"), "server-3: 2/7 * 70 = 20");
        assertEquals(10, counts.get("server-4"), "server-4: 1/7 * 70 = 10");
    }
    
    @Test
    void testZeroWeight() {
        // server-4의 가중치를 0으로 설정 (최소 1로 보정되어야 함)
        servers.get(3).setWeight(0);
        strategy.initialize(servers);
        
        // 가중치 0도 최소 1로 처리되는지 확인
        assertTrue(strategy.getWeightedListSize() >= servers.size(), 
                "가중치 0인 서버도 최소 1의 가중치를 가져야 합니다.");
    }
    
    @Test
    void testWeightDistributionString() {
        String distribution = strategy.getWeightDistribution(servers);
        System.out.println("가중치 분배: " + distribution);
        
        assertTrue(distribution.contains("server-1:40.0%"), "server-1은 40% 비율을 가져야 합니다.");
        assertTrue(distribution.contains("server-2:30.0%"), "server-2는 30% 비율을 가져야 합니다.");
        assertTrue(distribution.contains("server-3:20.0%"), "server-3은 20% 비율을 가져야 합니다.");
        assertTrue(distribution.contains("server-4:10.0%"), "server-4는 10% 비율을 가져야 합니다.");
    }
}
