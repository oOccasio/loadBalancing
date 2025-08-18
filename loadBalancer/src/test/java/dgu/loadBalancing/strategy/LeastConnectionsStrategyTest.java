package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LeastConnectionsStrategyTest {
    
    private LeastConnectionsStrategy strategy;
    private List<Server> servers;
    
    @BeforeEach
    void setUp() {
        strategy = new LeastConnectionsStrategy();
        
        servers = Arrays.asList(
            new Server("server-1", "localhost", 5001),
            new Server("server-2", "localhost", 5002),
            new Server("server-3", "localhost", 5003),
            new Server("server-4", "localhost", 5004)
        );
        
        // 모든 연결 수 초기화
        strategy.resetAllConnections(servers);
    }
    
    @Test
    void testSelectsServerWithLeastConnections() {
        // 서버별로 다른 연결 수 설정
        servers.get(0).incrementConnections(); // server-1: 1
        servers.get(0).incrementConnections(); // server-1: 2
        servers.get(1).incrementConnections(); // server-2: 1
        servers.get(2).incrementConnections(); // server-3: 1
        servers.get(2).incrementConnections(); // server-3: 2
        servers.get(2).incrementConnections(); // server-3: 3
        // server-4: 0 (가장 적음)
        
        Server selected = strategy.selectServer(servers, "client-1");
        assertEquals("server-4", selected.getId(), "연결 수가 0인 server-4가 선택되어야 합니다.");
        assertEquals(1, selected.getCurrentConnections(), "선택 후 연결 수가 1 증가해야 합니다.");
    }
    
    @Test
    void testDistributionWithEqualConnections() {
        // 모든 서버 연결 수가 동일할 때의 분배 테스트
        Map<String, Integer> counts = new HashMap<>();
        
        for (int i = 0; i < 20; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            counts.merge(selected.getId(), 1, Integer::sum);
            
            // 즉시 연결 종료하여 연결 수를 동일하게 유지
            strategy.updateServerMetrics(selected, 100L, true);
        }
        
        // 모든 서버가 비슷하게 선택되어야 함 (ID 순서로 선택될 것)
        System.out.println("동일 연결 수일 때 분배 결과: " + counts);
        
        // 각 서버가 최소 1번은 선택되어야 함
        counts.values().forEach(count -> 
            assertTrue(count > 0, "모든 서버가 최소 1번은 선택되어야 합니다."));
    }
    
    @Test
    void testLongRunningConnections() {
        // 장시간 연결 시뮬레이션
        Map<String, Integer> activeConnections = new HashMap<>();
        
        // 10개의 장시간 연결 시작
        for (int i = 0; i < 10; i++) {
            Server selected = strategy.selectServer(servers, "long-client-" + i);
            activeConnections.merge(selected.getId(), 1, Integer::sum);
            // 연결 종료하지 않음 (장시간 연결)
        }
        
        System.out.println("장시간 연결 분배: " + activeConnections);
        System.out.println(strategy.getConnectionStatus(servers));
        
        // 연결이 균등하게 분배되었는지 확인 (오차 ±2 허용)
        double average = 10.0 / servers.size(); // 2.5
        activeConnections.values().forEach(count -> 
            assertTrue(Math.abs(count - average) <= 2, 
                "연결이 비교적 균등하게 분배되어야 합니다."));
    }
    
    @Test
    void testServerWithMostConnectionsAvoidance() {
        // 한 서버에 많은 연결을 집중시킨 후 새 요청이 다른 서버로 가는지 확인
        Server busyServer = servers.get(0);
        
        // server-1에 5개 연결
        for (int i = 0; i < 5; i++) {
            busyServer.incrementConnections();
        }
        
        // 새 요청들은 다른 서버들로 가야 함
        for (int i = 0; i < 6; i++) {
            Server selected = strategy.selectServer(servers, "new-client-" + i);
            assertNotEquals("server-1", selected.getId(), 
                "바쁜 서버는 선택되지 않아야 합니다.");
            
            // 짧은 연결로 즉시 종료
            strategy.updateServerMetrics(selected, 100L, true);
        }
    }
    
    @Test
    void testUnhealthyServerExclusion() {
        // server-2를 비활성화하고 연결 수를 0으로 설정
        servers.get(1).setHealthy(false);
        
        // 다른 서버들에 연결 추가
        servers.get(0).incrementConnections(); // server-1: 1
        servers.get(2).incrementConnections(); // server-3: 1
        servers.get(3).incrementConnections(); // server-4: 1
        
        // server-2는 연결 수가 0이지만 비활성 상태이므로 선택되지 않아야 함
        for (int i = 0; i < 5; i++) {
            Server selected = strategy.selectServer(servers, "client-" + i);
            assertNotEquals("server-2", selected.getId(), 
                "비활성 서버는 선택되지 않아야 합니다.");
            
            strategy.updateServerMetrics(selected, 100L, true);
        }
    }
    
    @Test
    void testConcurrentConnections() throws InterruptedException {
        // 동시 연결 처리 테스트
        int numberOfThreads = 10;
        int requestsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        
        Map<String, Integer> counts = new HashMap<>();
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    Server selected = strategy.selectServer(servers, 
                            "thread-" + threadId + "-client-" + j);
                    
                    synchronized (counts) {
                        counts.merge(selected.getId(), 1, Integer::sum);
                    }
                    
                    // 짧은 처리 시간 시뮬레이션
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    strategy.updateServerMetrics(selected, 50L, true);
                }
            });
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), 
                "모든 스레드가 10초 내에 완료되어야 합니다.");
        
        System.out.println("동시 요청 처리 결과: " + counts);
        System.out.println(strategy.getTotalRequestStats(servers));
        
        // 모든 요청이 처리되었는지 확인
        int totalProcessed = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(numberOfThreads * requestsPerThread, totalProcessed, 
                "모든 요청이 처리되어야 합니다.");
    }
    
    @Test
    void testConnectionStatusMonitoring() {
        // 연결 상태 모니터링 기능 테스트
        servers.get(0).incrementConnections(); // server-1: 1
        servers.get(0).incrementConnections(); // server-1: 2
        servers.get(2).incrementConnections(); // server-3: 1
        
        String status = strategy.getConnectionStatus(servers);
        System.out.println(status);
        
        assertTrue(status.contains("server-1:2"), "server-1의 연결 수가 2로 표시되어야 합니다.");
        assertTrue(status.contains("server-2:0"), "server-2의 연결 수가 0으로 표시되어야 합니다.");
        assertTrue(status.contains("server-3:1"), "server-3의 연결 수가 1로 표시되어야 합니다.");
        assertTrue(status.contains("server-4:0"), "server-4의 연결 수가 0으로 표시되어야 합니다.");
    }
}
