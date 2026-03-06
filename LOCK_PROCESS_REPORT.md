# lock-library 동작 프로세스

이 문서는 `lock-library`가 실제 런타임에서 어떻게 동작하는지,
각 프로세스의 역할이 무엇인지, 그리고 왜 그렇게 설계했는지를 설명합니다.

- 대상 버전: `0.1.0`
- 대상 모듈:
  - `lock-core`
  - `lock-spring-boot-starter`

## 1. 보고서 목적

이 라이브러리는 서비스 메서드에 `@Locked`만 선언하면
공통 동시성 제어를 AOP 계층에서 일관되게 적용하도록 설계되어 있습니다.

핵심 목표는 아래 3가지입니다.

1. 도메인 서비스 코드에서 락 획득/해제 코드를 제거
2. `type-id` 기준으로 메서드 단위 동시성 제어를 표준화
3. 실패 시 예외 코드 체계를 일관되게 제공

## 2. 아키텍처 구성

### 2.1 모듈 책임 분리

- `lock-core`
  - `@Locked`, `@LockList`
  - `LockAspect` (AOP 진입/해제 오케스트레이션)
  - `LockService` (실제 인메모리 락 레지스트리)
  - `LockAcquireException`, `LockExceptionType` (실패 모델)
- `lock-spring-boot-starter`
  - `LockAutoConfiguration`
  - `AutoConfiguration.imports` 등록
  - 각 서비스에서 설정 클래스 없이 자동 사용 가능

## 3. 전체 동작 플로우

요청 하나가 `@Locked` 메서드로 들어오면 아래 순서로 처리됩니다.

1. Spring Boot가 starter의 `LockAutoConfiguration`을 자동 로딩
2. `LockAspect`가 대상 메서드 실행을 가로챔
3. 메서드에 선언된 `@Locked` 목록을 수집
4. `idIndex` 기반으로 실제 ID 추출, `type` 정규화
5. `type-id` 키로 락 타겟 목록 생성 (중복 키 제거)
6. 락 어노테이션 선언 순서대로 락 획득 (`tryLock`)
7. 비즈니스 메서드 실행 (`pjp.proceed()`)
8. `finally`에서 역순으로 락 해제
9. 유휴 락은 레지스트리에서 정리

## 4. 프로세스별 상세 동작

### 4.1 자동 설정 초기화

`LockAutoConfiguration`이 다음 조건에서 활성화됩니다.

- `org.aspectj.lang.ProceedingJoinPoint` 클래스가 classpath에 존재
- `library.lock.enabled=true`이거나 설정 누락(matchIfMissing=true)

생성되는 기본 빈:

- `LockService`
- `LockAspect`

설계 이유:

- 소비 서비스가 별도 `@Configuration`을 만들 필요 없음
- `@ConditionalOnMissingBean`으로 커스텀 구현 확장 가능
- `library.lock.enabled=false`로 운영 중 기능 제어 가능

### 4.2 애노테이션 수집

`LockAspect.withLock()` 진입 후 실행되는 일:

1. 프록시 환경을 고려해 실제 메서드를 다시 조회(`findToMethod`)
2. `method.getAnnotationsByType(Locked.class)`로 repeatable 애노테이션 수집
3. 애노테이션이 없으면 즉시 원본 메서드 실행

설계 이유:

- JDK/CGLIB 프록시 차이로 인한 메타데이터 누락 위험 감소
- `@Locked` 복수 선언을 자연스럽게 처리

### 4.3 락 타겟 계산

각 애노테이션에 대해 다음 계산을 수행합니다.

1. `type` 정규화: `trim + uppercase`
2. `idIndex` 위치 인자 추출
3. 지원 타입 변환: `Integer`, `Long`, `String`
4. 최종 타겟 키 생성: `"{TYPE}-{ID}"`
5. 중복 키는 `Set`으로 제거

중요 동작:

- `idIndex` 범위 오류 -> `LOCK_ID_INDEX_FAILED`
- 지원하지 않는 타입 -> `LOCK_ID_TYPE_UNSUPPORTED`
- `null` 또는 blank ID -> 해당 타겟은 스킵

설계 이유:

- 키 정규화로 대소문자/공백 차이에 의한 락 분산 방지
- 중복 제거로 같은 메서드 내 불필요한 락 재획득 방지

### 4.4 락 획득

`acquireLocks()`는 타겟 목록을 순서대로 획득합니다.

- 내부 서비스 호출: `lockService.acquire(type, id, retry)`
- `LockService`는 `ConcurrentHashMap`에서 키별 `ReentrantLock` 재사용/생성
- 획득 방식: `tryLock()` 즉시 시도 (대기 없음)

실패 정책:

- `retry=true` -> `AGAIN_REQUEST_PLEASE (20000)`
- `retry=false` -> `LOCK_ACQUIRE_FAILED (20001)`

중간 실패 시 보장:

- 이미 획득한 락을 즉시 역순 해제
- 즉, 부분 획득 상태를 남기지 않음

설계 이유:

- 대기형 락보다 빠른 실패로 API 응답 예측 가능성 확보
- 교착 상태 대응 비용을 낮추고 클라이언트 측 정책(재시도/즉시 실패)을 명시화

### 비즈니스 실행 및 해제

락 획득이 끝나면 원본 메서드를 실행합니다.

- 성공/실패와 무관하게 `finally`에서 해제
- 해제 순서: 락 획득 역순

`LockService.release()` 동작:

1. `unlock()`
2. `cleanup(key, lock)` 실행
3. `!isLocked && !hasQueuedThreads`이면 map에서 제거

설계 이유:

- `finally`로 누수 방지
- 유휴 락 정리로 장기 실행 시 메모리 사용 안정화

## 5. 클래스별 역할 정의

### 5.1 `Locked`

- 메서드 단위 락 정책 선언
- 입력값: `type`, `idIndex`, `retry`
- 반복 선언 허용(`@Repeatable(LockList.class)`)

### 5.2 `LockList`

- `@Locked` 반복 선언 컨테이너
- 사용자 직접 사용보다 컴파일러 내부 변환 용도

### 5.3 `LockAspect`

- 락 선언 해석 + 락 생명주기 담당
- 획득/실행/해제의 공통 파이프라인 보장
- `@Order(Ordered.HIGHEST_PRECEDENCE)`로 우선 적용

### 5.4 `LockService`

- 키 기반 락 레지스트리
- 획득/해제/정리 책임
- 키 규칙(`type-id`)의 단일 소스

### 5.5 `LockExceptionType`, `LockAcquireException`

- 실패 원인 표준화
- 코드/메시지 일관성 유지
- 전역 예외 핸들러와 쉽게 통합 가능

### 5.6 `LockAutoConfiguration`

- starter 진입점
- 기본 빈 자동 등록
- 조건부 활성화/비활성화 제어

## 6. 예외 코드 체계

- `AGAIN_REQUEST_PLEASE (20000)`
  - 락 경합, 재시도 권장
- `LOCK_ACQUIRE_FAILED (20001)`
  - 락 경합, 즉시 실패 정책
- `LOCK_ID_FAILED (20002)`
  - ID 누락/공백
- `LOCK_TYPE_FAILED (20003)`
  - type 누락/공백
- `LOCK_ID_INDEX_FAILED (20004)`
  - `idIndex` 설정 오류
- `LOCK_ID_TYPE_UNSUPPORTED (20005)`
  - 미지원 ID 타입

## 7. 핵심 설계 의도

### 7.1 왜 AOP + 애노테이션인가

- 중복 서비스 코드를 AOP로 관리하여 동시성 제어
- 메서드 선언만 보고 정책을 파악할 수 있어 유지보수 용이
- 인터셉터와 다르게 세밀한 제어가 가능

### 7.2 왜 `tryLock` 즉시 실패인가

- 대기 큐 기반 처리보다 API 지연을 짧고 예측 가능하게 유지
- 실패를 빠르게 반환하고, 클라이언트 측에서 결정

### 7.3 왜 역순 해제인가

- 복수 락 환경에서 획득/해제 짝을 명확하게 맞춤
- 부분 실패 시 롤백 로직 단순화

### 7.4 왜 키 정규화/중복 제거인가

- `team`, `TEAM`, ` TEAM ` 같은 선언 편차를 같은 대상로 통합
- 동일 키 중복 선언으로 인한 불필요한 처리 방지

### 7.5 왜 유휴 락 cleanup인가

- 키가 계속 증가하는 서비스에서 map 누적을 완화
- 동시성 안정성과 메모리 사용량 균형 유지

## 8. 운영 관점 체크포인트

현재 구현은 단일 JVM 인스턴스 기준입니다.

- 같은 인스턴스 내부 동시성: 제어 가능
- 멀티 인스턴스/분산 환경: 인스턴스 간 제어 불가

분산 환경에서는 Redis/DB 기반 분산락으로 전환이 필요합니다.

## 9. 확장 포인트

확장 시나리오:

1. 분산 락 구현체 추가 (`LockService` 대체 빈)
2. 대기 시간 기반 획득 옵션(`tryLock(timeout)`) 추가

현재 구조는 `@ConditionalOnMissingBean` 기반이므로
커스텀 `LockService`/`LockAspect`로 점진 전환이 가능합니다.

---

문서 기준 버전: `0.1.0`
