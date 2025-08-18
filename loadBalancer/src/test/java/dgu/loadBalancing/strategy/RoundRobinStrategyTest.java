package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoundRobinStrategyTest {
    
    private RoundRobinStrategy strategy;
    private List<Server> servers;
    
    @BeforeEach
    void setUp() {
        strategy = new RoundRobinStrategy();
        
        servers = Arrays.asList(
            new Server("server-1", "localhost", 5001),
            new Server("server-2", "localhost", 5002),
            new Server("server-3", "localhost", 5003),
            new Server("server-4", "localhost", 5004)
        );
    }
    
    @Test
    void testRoundRobinDistribution() {
        // 12번 요청하여 각 서버가 3번씩 선택되는지 확인
        int[] counts = new int[4];
        
        for (int i = 0; i < 12; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            String serverId = selected.getId();
            
            // 서버 ID에서 인덱스 추출
            int serverIndex = Integer.parseInt(serverId.split("-")[1]) - 1;
            counts[serverIndex]++;
            
            // 요청 완료 시뮬레이션
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 각 서버가 정확히 3번씩 선택되었는지 확인
        for (int count : counts) {
            assertEquals(3, count, "각 서버는 동일한 횟수만큼 선택되어야 합니다.");
        }
    }
    
    @Test
    void testSequentialSelection() {
        strategy.reset();
        
        // 순차적으로 서버가 선택되는지 확인
        for (int i = 0; i < 8; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            String expectedServerId = "server-" + ((i % 4) + 1);
            assertEquals(expectedServerId, selected.getId());
            
            strategy.updateServerMetrics(selected, 100L, true);
        }
    }
    
    @Test
    void testEmptyServerList() {
        assertThrows(IllegalArgumentException.class, () -> {
            strategy.selectServer(Arrays.asList(), "client-1");
        });
    }
    
    @Test
    void testNullServerList() {
        assertThrows(IllegalArgumentException.class, () -> {
            strategy.selectServer(null, "client-1");
        });
    }
}
