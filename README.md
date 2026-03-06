# lock-library

`lock-library`는 Spring Boot 서비스에서 메서드 단위 동시성 제어를 적용하기 위한 라이브러리입니다.

- `lock-core`: `@Locked` 애노테이션, AOP, 락 서비스 핵심 로직
- `lock-spring-boot-starter`: 자동 설정(autoconfiguration) 모듈

## 1. 핵심 설명

이 라이브러리는 `type-id` 조합으로 JVM 메모리 락을 관리합니다.

- 예: `TEAM-3`, `MEMBER-7`
- 같은 키로 동시에 들어오는 요청은 한 번에 하나만 통과
- 락 획득 실패 시 `LockAcquireException` 발생

중요: 현재 구현은 단일 JVM 인스턴스 기준입니다.
즉, 여러 서버 인스턴스가 동시에 떠 있는 분산 환경에서는 Redis/DB 기반 분산락이 필요합니다.

## 2. 라이브러리 버전

현재 배포 기준:

- `group`: `com.library`
- `artifact`: `lock-spring-boot-starter`
- `version`: `0.1.0`

일반 라이브러리처럼 starter만 추가하면,
`lock-core`를 바로 사용 가능합니다.

## 3. 적용 프로세스

### 3.1 저장소 + 인증 설정
해당 라이브러리를 사용할 있는 권한을 체크한다.

`build.gradle` 예시:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/jism-dev/lock-library")
        credentials {
            username = findProperty('gpr.user') ?: System.getenv('GPR_USER')
            password = findProperty('gpr.key') ?: System.getenv('GPR_KEY')
        }
    }
    mavenCentral()
}
```

### 3.2 의존성 추가

```groovy
dependencies {
    implementation "com.library:lock-spring-boot-starter:0.1.0"
}
```

### 3.3 인증값 설정

프로젝트 내부 로컬 파일
- 프로젝트 루트에 `gradle.local.properties` 생성 (gitignore 처리 필수)

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT
```

서버 CI/CD 환경변수

- `GPR_USER`
- `GPR_KEY`

PAT 권한은 최소 `read:packages`가 필요합니다.
패키지가 private이면 패키지/레포 접근 권한도 함께 열려 있어야 합니다.

## 4. Spring 설정

자동 설정은 기본 활성화입니다.

- 기본값: `library.lock.enabled=true`
- 비활성화: `library.lock.enabled=false`

`application.yml` 예시:

```yaml
library:
  lock:
    enabled: true
```

## 5. 코드 적용 방법

### 5.1 단일 락

```java
@Locked(type = "TEAM", idIndex = 0)
public void joinTeam(Long teamId, Long memberId) {
    // business logic
}
```

- `idIndex`는 메서드 인자 위치(0-based)
- `idIndex=0`이면 첫 번째 인자에서 락 ID 추출
- `type` `id` 조합을 통해 최종 락 ID를 생성

### 5.2 복수 락 (메서드에 여러 개 선언)

```java
@Locked(type = "MEMBER", idIndex = 1)
@Locked(type = "TEAM", idIndex = 0)
public void joinTeam(Long teamId, Long memberId) {
    // business logic
}
```

- `@Locked`는 repeatable이라 여러 번 선언 가능
- 획득한 락은 메서드 종료 시 역순으로 해제

### 5.3 retry 옵션

```java
@Locked(type = "TEAM", idIndex = 0, retry = true)
public void updateTeam(Long teamId) {
    // business logic
}
```

- `retry=true`(기본): 경합 시 "잠시 후 다시 시도" 의미의 예외 발생
- `retry=false`: 즉시 실패 의미의 예외 발생

## 6. ID 추출 규칙 (중요)

현재 버전에서 `idIndex`로 지정한 값은 아래 타입만 지원합니다.

- `Long`
- `Integer`
- `String`

지원하지 않는 타입이면 `LOCK_ID_TYPE_UNSUPPORTED` 예외가 발생합니다.

## 7. 예외 처리 가이드

주요 예외 코드는 아래와 같습니다.

- `AGAIN_REQUEST_PLEASE (20000)`
- `LOCK_ACQUIRE_FAILED (20001)`
- `LOCK_ID_FAILED (20002)`
- `LOCK_TYPE_FAILED (20003)`
- `LOCK_ID_INDEX_FAILED (20004)`
- `LOCK_ID_TYPE_UNSUPPORTED (20005)`

애플리케이션 전역 예외 핸들러에서 `LockAcquireException`을 잡아
코드/메시지를 표준 응답 형식으로 매핑하는 것을 권장합니다.

## 8. Docker/EC2 배포 시 체크포인트

의존성 다운로드는 일반적으로 **CI 빌드 단계**에서 발생합니다.

- CI가 JAR/이미지를 만든다면:
  - CI에만 `GPR_USER`, `GPR_KEY` 있으면 됨
  - EC2 런타임 서버에는 불필요
- EC2에서 직접 `gradle build`를 돌린다면:
  - EC2에도 같은 인증값 필요

## 9. 로컬 개발 팁 (라이브러리 수정을 원할 경우)

프로젝트에서 라이브러리를 로컬 소스로 바로 붙이고 싶다면
`settings.gradle`에 조건부 `includeBuild`를 사용할 수 있습니다.

예시:

```groovy
def useLocalLock = gradle.startParameter.projectProperties.get('useLocalLock') == 'true'
def localLockBuild = file('../library/lock')
if (useLocalLock && localLockBuild.exists()) {
    includeBuild(localLockBuild)
}
```


## 10. 빠른 트러블슈팅

### Q1. `Could not find com.library:lock-spring-boot-starter:0.1.0`

- 라이브러리 `0.1.0`이 실제 publish 되었는지 확인
- `--refresh-dependencies`로 캐시 갱신
- 저장소 URL/소유자(`jism-dev/lock-library`) 확인

### Q2. `Username must not be null`

- `gpr.user`, `gpr.key` 또는 `GPR_USER`, `GPR_KEY` 누락
- CI secret 주입 여부 확인

### Q3. 애노테이션을 붙였는데 락이 안 걸리는 것 같음

- starter 의존성이 실제 classpath에 있는지 확인
- `library.lock.enabled`가 `false`가 아닌지 확인
- 프록시/AOP 적용 대상(보통 서비스 빈 public 메서드)인지 확인

---

문서 기준 버전: `0.1.0`
