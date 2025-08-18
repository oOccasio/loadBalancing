package dgu.loadBalancing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 헬스체크 스케줄링 활성화
public class LoadBalancingApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoadBalancingApplication.class, args);
	}

}
