# CLAUDE.md — Adaptive Load Balancer (지능형 로드밸런서)

## 프로젝트 개요

정적인 로드밸런싱의 한계를 해결하기 위해, **트래픽 패턴을 시계열로 분석하고 상황에 따라 알고리즘을 동적으로 선택하는 Adaptive Load Balancer**를 구현한다.

핵심 차별점:
- Runtime은 **deterministic rule-based** 의사결정 (빠르고 안정)
- AI Agent는 **offline 분석** 역할 (벤치마크 결과 → rule 자동 생성/개선)
- **Feedback Loop**: 실행 → 메트릭 수집 → AI 분석 → Rule 개선 → 다시 적용

## 기술 스택

- **Language**: Java 17+ (Virtual Threads 활용)
- **Framework**: Spring Boot 3.x
- **Load Balancing Algorithms**: 기존 6개 (RR, WRR, LC, WLC, IP Hash, Random) — 인터페이스 통일 완료
- **Metrics**: Micrometer + Prometheus (시계열 메트릭 수집)
- **Benchmark**: K6 (부하 테스트)
- **AI Agent**: Python + OpenAI API (offline 분석 스크립트, GPT-4o)
- **Config**: YAML 기반 rule config (AI가 생성 → runtime이 읽음)
- **Monitoring**: Grafana (선택)

## 아키텍처

```
[Client Requests]
       ↓
[Adaptive Load Balancer] ← [Rule Config (YAML)]
  ├─ Metrics Collector (sliding window)
  ├─ Pattern Analyzer (state 분류)
  ├─ Decision Engine (state → algorithm 매핑)
  └─ Algorithm Executor (실제 분배)
       ↓
[Backend Servers (simulated)]

--- Offline Feedback Loop ---

[K6 Benchmark] → [Metrics Log (JSON)]
       ↓
[AI Agent (Python + OpenAI API)]
       ↓
[분석 결과 → Rule Config 업데이트]
```

## 프로젝트 구조

```
adaptive-load-balancer/
├── CLAUDE.md
├── README.md
├── docker-compose.yml
│
├── lb-core/                          # Spring Boot 메인 모듈
│   ├── src/main/java/com/alb/
│   │   ├── AlbApplication.java
│   │   │
│   │   ├── algorithm/                # 로드밸런싱 알고리즘
│   │   │   ├── LoadBalancer.java          # 공통 인터페이스
│   │   │   ├── RoundRobinBalancer.java
│   │   │   ├── WeightedRoundRobinBalancer.java
│   │   │   ├── LeastConnectionsBalancer.java
│   │   │   ├── WeightedLeastConnectionsBalancer.java
│   │   │   ├── IpHashBalancer.java
│   │   │   └── RandomBalancer.java
│   │   │
│   │   ├── metrics/                  # 메트릭 수집
│   │   │   ├── MetricsCollector.java      # sliding window 기반 수집
│   │   │   ├── MetricsSnapshot.java       # 특정 시점 메트릭 스냅샷
│   │   │   └── MetricsExporter.java       # JSON 로그 내보내기
│   │   │
│   │   ├── analyzer/                 # 패턴 분석 (State 분류)
│   │   │   ├── TrafficState.java          # enum: LOW, STABLE, SPIKE, OVERLOADED, GRADUAL
│   │   │   ├── PatternAnalyzer.java       # 메트릭 → state 변환 (derivative 기반)
│   │   │   ├── DerivativeCalculator.java  # 시계열 변화율 계산 (RPS/latency 미분)
│   │   │   └── AnalyzerConfig.java        # threshold 설정
│   │   │
│   │   ├── engine/                   # 의사결정 엔진
│   │   │   ├── DecisionEngine.java        # state → algorithm 선택
│   │   │   ├── DecisionResult.java        # 선택 결과 + reason (Explainability)
│   │   │   ├── DecisionLog.java           # 의사결정 이력 기록/조회
│   │   │   ├── DecisionRule.java          # 개별 rule 정의
│   │   │   ├── RuleConfigLoader.java      # YAML config 로딩 (버전 관리 포함)
│   │   │   └── SwitchPolicy.java          # cooldown + confidence + hysteresis 정책
│   │   │
│   │   ├── server/                   # 백엔드 서버 시뮬레이션
│   │   │   ├── BackendServer.java         # 가상 서버
│   │   │   └── ServerPool.java            # 서버 풀 관리
│   │   │
│   │   └── proxy/                    # 요청 프록시
│   │       └── RequestProxy.java          # 실제 요청 분배 + 메트릭 기록
│   │
│   └── src/main/resources/
│       ├── application.yml
│       └── decision-rules.yml             # AI가 생성하는 rule config
│
├── ai-agent/                         # AI Offline 분석 모듈
│   ├── requirements.txt                   # openai, pandas, pyyaml
│   ├── analyze.py                         # 메인 분석 스크립트
│   ├── rule_generator.py                  # 벤치마크 → rule YAML 생성
│   ├── rule_diff.py                       # rule 버전 간 diff + 변경 사유 생성
│   ├── pattern_classifier.py              # 시계열 → 패턴 분류
│   ├── post_analyzer.py                   # 실험 후 성능 분석 + 개선안
│   ├── prompts/
│   │   ├── rule_generation.txt            # rule 생성 프롬프트 템플릿
│   │   ├── pattern_analysis.txt           # 패턴 분류 프롬프트 템플릿
│   │   └── post_analysis.txt              # 사후 분석 프롬프트 템플릿
│   └── rule_versions/                     # Rule 버전 관리
│       ├── v1.yml                         # 초기 수동 rule
│       ├── v2.yml                         # AI 1차 개선
│       └── diff_v1_v2.md                  # 변경 이유 문서
│
├── benchmark/                        # K6 부하 테스트
│   ├── scenarios/
│   │   ├── stable-traffic.js              # 균등 트래픽
│   │   ├── spike-traffic.js               # 스파이크
│   │   ├── gradual-increase.js            # 점진적 증가
│   │   └── hotspot-traffic.js             # 특정 서버 편중
│   ├── results/                           # 벤치마크 결과 JSON
│   └── compare.sh                         # 고정 vs adaptive 비교 스크립트
│
└── docs/
    ├── architecture.md                    # 아키텍처 설명
    ├── decision-flow.md                   # 의사결정 흐름
    └── benchmark-results.md               # 실험 결과 정리
```

## 구현 순서 (이 순서대로 진행)

### Phase 1: Core LB + Metrics (Day 1 오전)
1. `lb-core` Spring Boot 프로젝트 생성
2. 기존 6개 알고리즘 코드 가져와서 `algorithm/` 패키지에 배치
3. `LoadBalancer` 인터페이스: `Server select(List<Server> servers)`
4. `BackendServer` 시뮬레이션 — 각 서버별 인위적 latency/에러율 설정 가능
5. `MetricsCollector` — sliding window (기본 10초) 기반으로 RPS, avg latency, p95 latency, error rate 수집
6. `RequestProxy` — HTTP 요청 받으면 → 현재 알고리즘으로 서버 선택 → 프록시 → 메트릭 기록
7. 간단한 REST API로 요청 받아서 분배하는 것까지 동작 확인

### Phase 2: Pattern Analyzer + Decision Engine (Day 1 오후)
1. `TrafficState` enum 정의:
    - `LOW_TRAFFIC`: RPS < threshold
    - `HIGH_STABLE`: RPS 높고 latency/error 안정
    - `SPIKE`: RPS 급증 (이전 윈도우 대비 증가율 기준)
    - `OVERLOADED_NODE`: 특정 서버 error rate/latency 비정상
    - `GRADUAL_INCREASE`: RPS 완만하게 증가 추세
2. `DerivativeCalculator` 구현:
    - 단순 threshold가 아니라 **시계열 변화율(derivative)**로 상태 판단
    - `rpsChangeRate = (current - previous) / previous`
    - `latencyTrend = 최근 N개 윈도우의 latency 기울기`
    - 변화율 기반이므로 절대값 threshold보다 트래픽 규모에 독립적
3. `PatternAnalyzer` 구현:
    - `DerivativeCalculator` 결과 + `MetricsSnapshot` → `TrafficState` 출력
    - threshold는 `AnalyzerConfig`에서 YAML로 관리
4. `DecisionResult` record 구현 **(Explainability 핵심)**:
   ```java
   public record DecisionResult(
       TrafficState state,
       AlgorithmType selectedAlgorithm,
       double confidence,
       String reason,           // "왜 이 알고리즘을 선택했는지" 근거
       Instant timestamp,
       MetricsSnapshot snapshot // 판단 시점의 메트릭
   ) {}
   ```
    - 모든 의사결정은 `DecisionLog`에 기록
    - 예시 로그:
   ```
   [Decision] state=SPIKE algorithm=LEAST_CONNECTIONS confidence=0.87
   reason="RPS increased 120% (derivative=1.2), p95 latency spiked to 450ms.
           LC showed 35% lower latency than RR in SPIKE benchmark."
   ```
5. `DecisionEngine` 구현:
    - `decision-rules.yml`에서 state → algorithm 매핑 로딩
    - 매 결정마다 `DecisionResult`를 생성하여 reason 포함
6. `SwitchPolicy` 구현 — **cooldown + confidence + hysteresis**:
    - cooldown (기본 10초) + confidence score (0.0~1.0)
    - **Hysteresis**: 상태가 N개 연속 윈도우(기본 3) 동안 유지되어야 전환
    - 일시적 튀는 값(노이즈)으로 인한 불필요한 전환 방지
   ```java
   // Hysteresis: 새 상태가 sustainedWindows 연속 유지되어야 전환
   if (consecutiveStateCount(newState) < sustainedWindows) return false;
   ```
7. 전체 흐름 연결: 요청 → 메트릭 수집 → 변화율 계산 → 패턴 분석 → 알고리즘 선택(+reason 기록) → 분배

### Phase 3: K6 Benchmark (Day 1 저녁)
1. K6 시나리오 4개 작성 (stable, spike, gradual, hotspot)
2. 고정 알고리즘 (RR, LC, WRR 각각) vs Adaptive 성능 비교
3. 결과를 JSON으로 export → `benchmark/results/`에 저장
4. 메트릭 로그도 JSON으로 export → AI agent 입력용

### Phase 4: AI Agent Offline Analysis (Day 2 오전)
1. `analyze.py`: 벤치마크 결과 JSON 읽기 → OpenAI API (GPT-4o)로 분석 요청
2. `rule_generator.py`: GPT가 생성한 rule → `decision-rules.yml` 형식으로 변환 → `rule_versions/`에 새 버전으로 저장
3. `rule_diff.py`: **Rule Versioning & Diff** — 이전 버전과 비교하여 변경 사항 + 변경 이유 자동 생성
    - 출력 예시 (`diff_v1_v2.md`):
   ```
   ## Rule Changes: v1 → v2
   
   | State | v1 Algorithm | v2 Algorithm | Reason |
   |-------|-------------|-------------|--------|
   | SPIKE | ROUND_ROBIN | LEAST_CONNECTIONS | Latency improved 35% in spike benchmark |
   | OVERLOADED | WRR | WLC | Error rate reduced from 5.2% to 1.1% |
   ```
4. `pattern_classifier.py`: 시계열 메트릭 → GPT에게 패턴 분류 요청
5. `post_analyzer.py`: 실험 후 전환 로그 + DecisionResult 이력 + 성능 데이터 → GPT가 문제점/개선안 도출
6. Feedback Loop 실행: AI 분석 결과로 rule 업데이트 (새 버전) → 다시 벤치마크 → 비교

### Phase 5: Documentation + README (Day 2 오후)
1. README.md 작성 (아래 스토리라인 참고)
2. 아키텍처 다이어그램
3. 벤치마크 결과 비교표/그래프
4. "Before AI → After AI" rule 개선 전후 비교
5. Decision Explainability 로그 예시 포함
6. Rule 버전 diff 문서 포함

## decision-rules.yml 형식 예시

```yaml
rules:
  - state: SPIKE
    algorithm: LEAST_CONNECTIONS
    confidence_threshold: 0.8
    description: "RPS 급증 시 연결 수 기반 분배가 latency 안정화에 효과적"

  - state: HIGH_STABLE
    algorithm: ROUND_ROBIN
    confidence_threshold: 0.6
    description: "안정적 트래픽에서는 단순 분배가 오버헤드 최소"

  - state: OVERLOADED_NODE
    algorithm: WEIGHTED_LEAST_CONNECTIONS
    confidence_threshold: 0.9
    description: "특정 노드 과부하 시 가중치 기반으로 부하 회피"

  - state: LOW_TRAFFIC
    algorithm: ROUND_ROBIN
    confidence_threshold: 0.5
    description: "저트래픽에서는 알고리즘 차이 미미"

  - state: GRADUAL_INCREASE
    algorithm: WEIGHTED_ROUND_ROBIN
    confidence_threshold: 0.7
    description: "점진적 증가 시 가중치로 서버 capacity 반영"

switch_policy:
  cooldown_seconds: 10
  min_confidence: 0.6
  max_switches_per_minute: 3
  sustained_windows: 3          # hysteresis: 상태가 N개 윈도우 연속 유지 시 전환
  spike_sustained_windows: 2    # SPIKE는 빠른 대응 필요 → 예외
```

## MetricsSnapshot 구조

```java
public record MetricsSnapshot(
    long timestamp,
    double rps,
    double avgLatencyMs,
    double p95LatencyMs,
    double errorRate,
    Map<String, ServerMetrics> serverMetrics  // 서버별 개별 메트릭
) {}

public record ServerMetrics(
    double cpuUsage,
    double memoryUsage,
    double avgLatencyMs,
    double errorRate,
    int activeConnections
) {}
```

## DecisionResult 구조 (Explainability)

```java
public record DecisionResult(
    TrafficState state,
    AlgorithmType selectedAlgorithm,
    double confidence,
    String reason,              // 🔥 핵심: 왜 이 알고리즘인지 근거
    Instant timestamp,
    MetricsSnapshot snapshot
) {}
```

reason 생성 로직:
- PatternAnalyzer가 state 판단 근거를 문자열로 생성
- DecisionEngine이 rule 매칭 근거를 추가
- 예: `"RPS derivative=1.5 (>1.0 threshold) → SPIKE detected. LC selected: benchmark showed 35% lower p95 latency vs RR in SPIKE pattern."`

모든 DecisionResult는 `DecisionLog`에 시계열로 저장 → AI Agent의 post_analysis 입력으로 활용.

## SwitchPolicy 로직 (cooldown + hysteresis)

```java
public boolean shouldSwitch(TrafficState newState, TrafficState currentState,
                             double confidence, Instant lastSwitchTime) {
    if (newState == currentState) return false;
    if (confidence < minConfidence) return false;
    if (Duration.between(lastSwitchTime, Instant.now()).getSeconds() < cooldownSeconds) return false;
    if (recentSwitchCount() >= maxSwitchesPerMinute) return false;
    
    // 🔥 Hysteresis: 새 상태가 N개 연속 윈도우 동안 유지되어야 전환
    // → 일시적 노이즈로 인한 불필요한 전환 방지
    if (consecutiveStateCount(newState) < sustainedWindows) return false;
    
    return true;
}
```

hysteresis 설정:
- `sustained_windows: 3` (기본값, 즉 30초 동안 상태 유지 시 전환)
- SPIKE는 빠른 대응 필요 → `sustained_windows: 2`로 예외 설정 가능

## AI Agent 프롬프트 예시 (OpenAI GPT-4o 사용)

### Rule Generation (rule_generation.txt)
```
당신은 로드밸런서 전략 분석 전문가입니다.

아래 벤치마크 결과를 분석하고, 각 트래픽 상태(state)에서 최적의 로드밸런싱 알고리즘을 추천해주세요.

[벤치마크 결과]
{benchmark_data}

[사용 가능한 알고리즘]
ROUND_ROBIN, WEIGHTED_ROUND_ROBIN, LEAST_CONNECTIONS,
WEIGHTED_LEAST_CONNECTIONS, IP_HASH, RANDOM

[요구사항]
1. 각 트래픽 패턴별 최적 알고리즘과 근거
2. confidence_threshold 추천값
3. reason 필드에 "왜 이 알고리즘인지" 명확한 근거 포함
4. 주의사항이나 예외 케이스

JSON 형식으로 응답:
{
  "rules": [
    {
      "state": "...",
      "algorithm": "...",
      "confidence_threshold": 0.0,
      "reason": "구체적인 선택 근거 (수치 포함)"
    }
  ]
}
```

### Rule Diff Analysis (rule_diff.txt)
```
당신은 시스템 전략 변경 분석 전문가입니다.

아래 두 버전의 decision rule을 비교하고, 변경 사항과 그 이유를 분석해주세요.

[이전 Rule (v{old_version})]
{old_rules}

[새 Rule (v{new_version})]
{new_rules}

[벤치마크 비교 데이터]
{benchmark_comparison}

JSON 형식으로 응답:
{
  "changes": [
    {
      "state": "...",
      "old_algorithm": "...",
      "new_algorithm": "...",
      "reason": "변경 근거 (성능 수치 포함)",
      "expected_improvement": "예상 개선 효과"
    }
  ],
  "summary": "전체 변경 요약"
}
```

### Post Analysis (post_analysis.txt)
```
당신은 시스템 성능 분석 전문가입니다.

아래 Adaptive Load Balancer의 실행 로그를 분석하고 개선점을 도출해주세요.

[알고리즘 전환 로그 (DecisionResult 이력)]
{decision_log}

[성능 메트릭 시계열]
{metrics_timeseries}

[분석 요청]
1. 알고리즘 전환이 적절했는지 (불필요한 전환, 늦은 전환 등)
2. hysteresis / cooldown 파라미터 조정이 필요한 부분
3. threshold 조정이 필요한 부분
4. 성능 병목 구간과 원인
5. 구체적인 개선 방안

JSON 형식으로 응답해주세요.
```

## README 스토리라인

README는 이 흐름으로 구성:

### 1. 문제 인식
"기존 로드밸런서는 설정 시점에 알고리즘이 고정된다. 하지만 실제 트래픽은 시간대별로 패턴이 달라지고, 최적 알고리즘도 달라진다."

### 2. 접근 방식
"6개 알고리즘을 직접 구현하고 벤치마크한 결과, **트래픽 패턴에 따라 최적 알고리즘이 다르다**는 것을 데이터로 확인했다. 이를 기반으로 런타임에 알고리즘을 동적 전환하는 Adaptive LB를 설계했다."

### 3. 핵심 설계 결정
- **Why Rule-based?**: 런타임 안정성 (AI 호출 latency 없음)
- **Why AI Offline?**: 전략 진화의 자동화 (사람이 수동으로 rule 튜닝 → AI가 데이터 기반으로 개선)
- **Why Cooldown + Hysteresis?**: 잦은 전환으로 인한 시스템 불안정 방지 + 노이즈 필터링
- **Why Explainability?**: 모든 의사결정에 "왜 이 알고리즘인지" 근거를 기록하여 추적/감사 가능
- **Why Rule Versioning?**: AI가 rule을 개선할 때마다 변경 이유를 문서화 → 전략 진화 과정 추적

### 4. 실험 결과
- 고정 알고리즘 vs Adaptive: latency, error rate, throughput 비교
- AI 분석 전 rule vs AI 분석 후 rule: 성능 개선 수치

### 5. 배운 점 / 한계 / 확장
- State 분류 정확도의 한계
- 더 세밀한 상태 정의 필요성
- ML 모델로의 확장 가능성

## 코딩 컨벤션

- Java: Google Java Style Guide
- 패키지 구조: feature 기반 (algorithm, metrics, analyzer, engine)
- 테스트: JUnit 5, 각 컴포넌트 단위 테스트 필수
- 커밋: conventional commits (feat:, fix:, docs:, test:, refactor:)
- YAML config는 `src/main/resources/`에 위치

## 주의사항

- Runtime에 절대 AI/LLM API 호출하지 않는다 (latency 이슈)
- 알고리즘 전환은 SwitchPolicy를 반드시 거친다
- MetricsCollector의 sliding window 크기는 설정 가능하게
- 모든 벤치마크 결과는 JSON으로 저장하여 재현 가능하게
- AI Agent 분석 결과도 로그로 남긴다 (어떤 입력 → 어떤 출력)