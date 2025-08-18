package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import java.util.List;

/**
 * 로드밸런싱 알고리즘을 정의하는 전략 패턴 인터페이스
 */
public interface LoadBalancingStrategy {
    
    /**
     * 사용 가능한 서버 목록에서 하나의 서버를 선택
     * 
     * @param servers 사용 가능한 서버 목록 (건강한 서버들만)
     * @param clientInfo 클라이언트 정보 (IP, 세션 ID 등)
     * @return 선택된 서버
     * @throws IllegalArgumentException 사용 가능한 서버가 없는 경우
     */
    Server selectServer(List<Server> servers, String clientInfo);
    
    /**
     * 요청 완료 후 서버 메트릭 업데이트
     * 
     * @param server 요청을 처리한 서버
     * @param responseTime 응답시간 (밀리초)
     * @param success 요청 성공 여부
     */
    void updateServerMetrics(Server server, long responseTime, boolean success);
    
    /**
     * 알고리즘 이름 반환
     * 
     * @return 알고리즘 식별자
     */
    String getStrategyName();
    
    /**
     * 알고리즘 설명 반환
     * 
     * @return 알고리즘에 대한 간단한 설명
     */
    default String getDescription() {
        return "Load balancing strategy: " + getStrategyName();
    }
    
    /**
     * 알고리즘 초기화 (필요한 경우)
     * 
     * @param servers 초기 서버 목록
     */
    default void initialize(List<Server> servers) {
        // 기본적으로는 아무것도 하지 않음
        // 필요한 알고리즘에서 오버라이드
    }
    
    /**
     * 서버 추가 시 호출
     * 
     * @param server 추가된 서버
     */
    default void onServerAdded(Server server) {
        // 기본적으로는 아무것도 하지 않음
    }
    
    /**
     * 서버 제거 시 호출
     * 
     * @param server 제거된 서버
     */
    default void onServerRemoved(Server server) {
        // 기본적으로는 아무것도 하지 않음
    }
}
