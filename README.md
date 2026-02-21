# 🚀 로드밸런싱 구현 및 부하테스트 



![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

# ⚖️ Load Balancer — 6가지 알고리즘 구현 및 성능 비교 분석

> 6가지 로드밸런싱 알고리즘을 직접 구현하고, 3가지 시나리오에서 체계적으로 성능을 비교한 프로젝트

## 📌 프로젝트 개요

"어떤 로드밸런싱 알고리즘이 가장 좋은가?"라는 질문에 **데이터 기반으로 답하기 위한 프로젝트**입니다.

6가지 알고리즘을 Spring Boot로 직접 구현하고, Docker 기반 4대 서버 환경에서 K6 부하 테스트를 수행했습니다. Prometheus + Grafana로 8개 메트릭을 실시간 수집하여 알고리즘별 성능 특성, 장애 대응력, 부하 분산 패턴을 정량적으로 비교했습니다.

### 핵심 성과
- **Least Connections**가 RPS, 응답시간, 분산 균등성 모두에서 1위를 기록
- **Least Response Time**에서 **스노우볼 효과** 발견 — 이론적 최적 알고리즘이 실제로는 단일 장애점을 생성
- **IP Hash**가 장애 대응 에러율 0.00%(3건/41,320건)으로 사실상 무중단 달성
- 락프리 동시성 제어 설계 및 blocking I/O 환경에서의 병목 분석

---

## 🏗️ 시스템 아키텍처
![아키텍처](https://i.imgur.com/GxWs35d.png)

## 🛠️ 기술 스택

| 분류 | 기술 |
|------|------|
| 로드밸런서 | Spring Boot, Java 17, WebClient |
| 백엔드 서버 | Docker Compose (4개 컨테이너) |
| 부하 테스트 | K6 |
| 모니터링 | Prometheus, Grafana, Micrometer |
| 동시성 제어 | AtomicInteger, volatile, ConcurrentHashMap, ConcurrentSkipListMap, ThreadLocal, CopyOnWriteArrayList |

---

## ⚙️ 구현 알고리즘

| 알고리즘 | 분류 | 핵심 원리 |
|---------|------|----------|
| **Round Robin** | 정적 | 순차적 서버 선택 |
| **Weighted Round Robin** | 정적 | 가중치 기반 비례 분배 (6:3:2:1) |
| **Least Connections** | 동적 | 현재 활성 연결 수 최소인 서버 선택 |
| **IP Hash** | 해시 | 클라이언트 IP 해시로 서버 고정 + 캐시 매핑 |
| **Consistent Hashing** | 해시 | 해시 링 + 가상 노드(150개)로 최소 재배치 |
| **Least Response Time** | 동적 | 평균 응답시간 최저 서버 선택 (Circular Buffer) |

---

## 📊 성능 테스트 결과

### Steady Load (VU 100, 3분)

| 알고리즘 | RPS | avg (ms) | Active Conn 분포 |
|---------|-----|----------|------------------|
| Round Robin | 281.9 | 253.58 | 4 / 11 / 22 / 36 |
| Weighted RR | 387.4 | 157.10 | 6 / 14 / 18 / 16 |
| **Least Connections** | **421.0** | **136.58** | **14 / 14 / 14 / 14** |
| IP Hash | 281.9 | 253.61 | 2 / 13 / 19 / 32 |
| Consistent Hashing | 279.5 | 256.64 | 유사 패턴 |
| Least Response Time | 640.4* | 55.68* | 35 / 0 / 0 / 0 |

> *LRT는 server-1에 100% 집중 — 로드밸런싱이 아닌 단일 서버 선택

### Server Failure (server-1 다운)

| 알고리즘 | RPS | 에러율 | 에러 건수 | RPS 하락폭 |
|---------|-----|--------|----------|-----------|
| Round Robin | 229.7 | 0.83% | 345 | 18.5% |
| Weighted RR | 280.7 | 0.52% | 265 | 27.5% |
| Least Connections | **288.8** | 0.61% | 319 | 31.4% |
| **IP Hash** | 228.8 | **0.00%** | **3** | 18.8% |
| Consistent Hashing | 227.0 | 0.36% | 150 | 18.8% |
| Least Response Time | 422.4* | 0.03% | 23 | 34.0% |

---

## 🔍 핵심 발견 사항

### 1. Least Connections의 "동적 가중치 효과"

LC는 처리시간을 직접 측정하지 않지만, 빠른 서버가 연결을 빨리 해제 → 항상 최소 연결 유지 → 자연스럽게 더 많은 요청 처리. Active Connections가 **14/14/14/14로 완벽한 균등 분산**을 이루면서 동시에 **최고 RPS(421.0)**를 달성했다.

### 2. LRT의 스노우볼 효과 🌨️

Least Response Time이 가장 빠른 서버에 트래픽을 100% 집중시켜 **사실상 로드밸런싱이 동작하지 않는 현상**을 발견했다. 장애 발생 시에도 쏠림이 server-2로 이전될 뿐(Active Connections: 0/100/0/0), 근본적으로 해결되지 않았다.

```
정상: server-1에 100% → 장애 발생 → server-2에 100% (쏠림 이전)
```

### 3. IP Hash의 장애 대응

성능(RPS)에서는 Round Robin과 동일하지만, 장애 시 **에러 3건**(0.00%)으로 사실상 무중단을 달성했다. 캐시 매핑(`ipServerMapping.compute()`)에서 unhealthy 서버를 즉시 감지하고 재선택하기 때문이다.

### 4. p95는 알고리즘 차이를 반영하지 않는다

모든 알고리즘에서 p95 ≈ 502ms로 수렴. 가장 느린 서버(500ms)로 가는 요청이 항상 존재하기 때문. **알고리즘 간 차이는 avg, med, RPS에서 드러난다.**

---

## 🔒 동시성 제어 설계

`synchronized` 대신 **락프리(lock-free) 기반**으로 설계하여 컨텍스트 스위칭을 최소화했다.

| 구조 | 적용 위치 | 선택 이유 |
|------|----------|----------|
| AtomicInteger (CAS) | RR/WRR 인덱스 | 단일 카운터의 원자적 증가 |
| volatile | 서버 리스트 참조 | 멀티스레드 간 메모리 가시성 보장 |
| ConcurrentHashMap | IP Hash 캐시 | 키별 독립 락으로 경합 최소화 |
| ConcurrentSkipListMap | CH 해시 링 | 정렬된 구조의 동시 접근 |
| ThreadLocal | 시간 포맷터 | 스레드별 독립 인스턴스 |
| Copy-on-Write | 서버 리스트 변경 | 읽기 >> 쓰기인 구조에 최적 |

**성능 검증**: VU 400(8,900 RPS) 환경에서 synchronized 대비 3% 이내 차이. 현재 구조에서는 WebClient.block()의 네트워크 I/O가 병목이어서 알고리즘 선택 구간(나노~마이크로초)의 경합이 전체 성능에 미치는 영향이 0.01% 미만. Nginx처럼 non-blocking 이벤트 루프 기반에서는 락프리 설계가 유의미해진다.

---

## 📈 모니터링 시스템

Prometheus + Grafana 기반 **8개 커스텀 메트릭** 실시간 수집:

| 메트릭 | 설명 |
|--------|------|
| `requests_total` | 알고리즘/서버별 총 요청 수 |
| `response_time_seconds` | 서버별 응답시간 히스토그램 |
| `algorithm_duration_seconds` | 알고리즘 선택 소요시간 |
| `active_connections` | 서버별 현재 활성 연결 수 |
| `errors_total` | 에러 유형별 카운트 |
| `server_health` | 서버 헬스 상태 (0/1) |
| `backend_selection_total` | 서버 선택 횟수 |

---

## 🚀 실행 방법

### 1. 백엔드 서버 실행
```bash
docker-compose up -d
```

### 2. 모니터링 실행
```bash
cd monitoring
docker-compose up -d
```

### 3. 로드밸런서 실행
```bash
./gradlew bootRun
```

### 4. 부하 테스트
```bash
# Steady Load
k6 run k6/steady_load.js

# Burst
k6 run k6/burst_load.js

# Server Failure (테스트 중 docker stop web-server-1)
k6 run k6/server_failure.js
```

### 5. 대시보드 확인
- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090


## 🎯 상황별 알고리즘 선택 가이드

| 상황 | 추천 알고리즘 | 근거 |
|------|-------------|------|
| 서버 스펙 동일 | Round Robin | 구현 단순, 균등 분배 |
| 서버 스펙 상이 | Weighted RR | RR 대비 RPS 37%↑ |
| 범용 웹 서비스 | **Least Connections** | RPS·응답시간·균등성 모두 1위 |
| 세션 유지 필요 | IP Hash | 장애 대응 에러율 0.00% |
| 서버 자주 변경 | Consistent Hashing | 최소 재배치 |

---

## 📝 향후 개선 방향

- **재시도 로직**: 요청 실패 시 다른 서버로 1회 재시도하여 에러율 추가 감소
- **LRT 스노우볼 방지**: 응답시간 × 활성연결수 복합 점수 도입
- **ML 기반 알고리즘**: 실시간 메트릭 기반 동적 알고리즘 전환
