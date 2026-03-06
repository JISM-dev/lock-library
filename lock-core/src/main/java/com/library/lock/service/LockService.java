package com.library.lock.service;

import com.library.lock.exception.LockAcquireException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.library.lock.exception.LockExceptionType.*;

/**
 * 메모리 기반 락 레지스트리 서비스.
 *
 * <p>키 단위로 {@link ReentrantLock}을 관리하며, 같은 키에 대한 중복 진입을 방지한다.
 * 현재 구현은 단일 JVM 인스턴스 기준 동시성 제어를 목표로 하며,
 * 멀티 인스턴스(분산 환경) 락은 별도 구현이 필요하다.</p>
 */
public class LockService {

    /**
     * 타입과 ID를 결합할 때 사용하는 고정 구분자.
     */
    private static final String KEY_DELIMITER = "-";
    /**
     * "락 키 -> 락 인스턴스" 매핑 저장소.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 지정한 타입/ID 조합의 락을 즉시 획득한다.
     *
     * <p>동작 순서:</p>
     * <p>1) {@link #createKey(String, String)}로 표준 락 키 생성</p>
     * <p>2) 레지스트리에서 기존 락 재사용 또는 지연 생성</p>
     * <p>3) {@link ReentrantLock#tryLock()}으로 즉시 획득 시도</p>
     * <p>4) 획득 실패 시 retry 설정에 맞는 예외 반환</p>
     *
     * <p>{@code retry=true}면 재시도 유도 예외({@code AGAIN_REQUEST_PLEASE}),
     * {@code retry=false}면 즉시 실패 예외({@code LOCK_ACQUIRE_FAILED})를 사용한다.</p>
     *
     * @param type 락 타입(네임스페이스)
     * @param id 락 대상 ID
     * @param retry 획득 실패 시 재시도 가능 여부
     * @return 해제 시 필요한 핸들
     * @throws LockAcquireException 타입/ID 검증 실패 또는 락 획득 실패 시
     */
    public LockHandle acquire(String type, String id, boolean retry) {
        String key = createKey(type, id);
        ReentrantLock lock = locks.computeIfAbsent(
                key,
                ignored -> new ReentrantLock()
        );

        if (!lock.tryLock()) {
            throw new LockAcquireException(retry ? AGAIN_REQUEST_PLEASE : LOCK_ACQUIRE_FAILED);
        }

        return new LockHandle(key, lock);
    }

    /**
     * 락을 해제하고, 유휴 락이면 레지스트리에서 정리한다.
     *
     * <p>해제 도중 예외가 발생하더라도 정리 로직이 누락되지 않도록
     * {@code finally} 블록에서 {@link #cleanup(String, ReentrantLock)}을 호출한다.</p>
     *
     * @param handle {@link #acquire(String, String, boolean)} 결과 핸들
     */
    public void release(LockHandle handle) {
        if (handle == null || handle.lock() == null) {
            return;
        }
        try {
            handle.lock().unlock();
        } finally {
            cleanup(handle.key(), handle.lock());
        }
    }

    /**
     * 락 인스턴스가 완전히 유휴 상태일 때만 레지스트리에서 제거한다.
     *
     * <p>제거 조건:</p>
     * <p>- 어떤 스레드도 락을 보유하지 않을 것</p>
     * <p>- 대기 큐에 스레드가 없을 것</p>
     *
     * @param key 락 키
     * @param lock 정리 대상 락 인스턴스
     */
    private void cleanup(String key, ReentrantLock lock) {
        if (key == null || lock == null) {
            return;
        }

        if (!lock.isLocked() && !lock.hasQueuedThreads()) {
            locks.remove(key, lock);
        }
    }

    /**
     * 타입과 ID를 결합해 표준 락 키를 생성한다.
     *
     * <p>같은 패키지의 AOP 계층에서도 동일 규칙으로 키를 만들기 때문에,
     * 중복 락 대상 판별 기준을 일관되게 유지할 수 있다.</p>
     *
     * @param type 락 타입
     * @param id 락 ID
     * @return "{type}-{id}" 형태의 키 문자열
     * @throws LockAcquireException type 또는 id가 비어 있으면 발생
     */
    public String createKey(String type, String id) {
        if (id == null || id.isBlank()) {
            throw new LockAcquireException(LOCK_ID_FAILED);
        }

        if (type == null || type.isBlank()) {
            throw new LockAcquireException(LOCK_TYPE_FAILED);
        }

        return type + KEY_DELIMITER + id.trim();
    }

    /**
     * 레지스트리에 남아 있는 모든 락을 강제로 초기화한다.
     *
     * <p>테스트나 애플리케이션 종료/리셋 시나리오에서 사용한다.</p>
     */
    public void cleanAll() {
        locks.clear();
    }

    /**
     * 락 키와 락 인스턴스를 함께 보관하는 획득 결과 객체.
     *
     * <p>Aspect 계층에서 획득한 순서대로 저장해 두었다가,
     * 메서드 종료 시 역순 해제할 때 사용한다.</p>
     */
    public record LockHandle(String key, ReentrantLock lock) {
    }
}
