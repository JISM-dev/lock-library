package com.library.lock.aop;

import com.library.lock.annotation.LockList;
import com.library.lock.annotation.Locked;
import com.library.lock.exception.LockAcquireException;
import com.library.lock.service.LockService;
import com.library.lock.service.LockService.LockHandle;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.library.lock.exception.LockExceptionType.LOCK_ID_INDEX_FAILED;
import static com.library.lock.exception.LockExceptionType.LOCK_ID_TYPE_UNSUPPORTED;
import static com.library.lock.exception.LockExceptionType.LOCK_TYPE_FAILED;

/**
 * {@link Locked} / {@link LockList} 애노테이션 기반 메서드 락 AOP.
 *
 * <p>요청 메서드 실행 전 락 대상을 계산해 선점하고, 실행 후 항상 해제한다.
 * 락 획득/해제의 공통 흐름을 서비스 코드에서 제거해 사용자가 애노테이션만으로
 * 동시성 제어를 적용할 수 있게 해준다.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LockAspect {

    /**
     * 실제 락 획득/해제를 담당하는 레지스트리 서비스.
     */
    private final LockService lockService;

    /**
     * 락 Aspect 생성자.
     *
     * @param lockService 락 획득/해제를 수행하는 서비스
     */
    public LockAspect(LockService lockService) {
        this.lockService = lockService;
    }

    /**
     * 락 애노테이션이 선언된 메서드 실행을 감싼다.
     *
     * <p>처리 순서:</p>
     * <p>1) 프록시 환경을 고려해 실제 메서드 메타데이터 확인</p>
     * <p>2) 메서드에 선언된 {@link Locked} 목록 수집</p>
     * <p>3) 메서드 인자를 기반으로 실제 락 타겟 목록 생성</p>
     * <p>4) 선언 순서대로 락 획득 후 비즈니스 메서드 실행</p>
     * <p>5) finally에서 역순으로 락 해제</p>
     *
     * @param pjp AOP 조인포인트
     * @return 원본 메서드 반환값
     * @throws Throwable 원본 메서드/락 처리 과정에서 발생한 예외
     */
    @Around("@annotation(com.library.lock.annotation.Locked) || @annotation(com.library.lock.annotation.LockList)")
    public Object withLock(ProceedingJoinPoint pjp) throws Throwable {
        /* 1. 메서드 접근 */
        Method method = findToMethod(pjp);

        /* 2. Locked 어노테이션 리스트 추출 */
        List<Locked> lockAnnotations = findLockAnnotationList(method);

        /* 2-1. 비어있을 경우, 즉 어노테이션이 없을 경우 그대로 진행 */
        if (lockAnnotations.isEmpty()) {
            return pjp.proceed();
        }

        /* 3. 락 타겟 리스트 추출 */
        List<LockTarget> targetList = getLockTargetList(lockAnnotations, pjp.getArgs());

        /* 3-1. 비어있을 경우, 즉 락 타겟 리스트 없을 경우 그대로 진행 */
        if (targetList.isEmpty()) {
            return pjp.proceed();
        }

        List<LockHandle> handles = acquireLocks(targetList);
        try {
            return pjp.proceed();
        } finally {
            releaseLocks(handles);
        }
    }

    /**
     * 프록시(JDK/CGLIB) 방식과 무관하게 실제 실행 메서드를 찾는다.
     *
     * <p>가능하면 타깃 클래스의 concrete 메서드를 다시 조회해
     * 애노테이션 해석을 안정적으로 수행한다.</p>
     *
     * @param pjp AOP 조인포인트
     * @return 해석 대상 메서드, 찾지 못하면 null
     */
    private Method findToMethod(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature methodSignature)) {
            return null;
        }

        Method method = methodSignature.getMethod();
        if (method == null) {
            return null;
        }

        Object target = pjp.getTarget();
        if (target == null) {
            return method;
        }

        try {
            return target.getClass().getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException ignored) {
            return method;
        }
    }

    /**
     * 메서드에 선언된 {@link Locked} 애노테이션들을 모두 추출한다.
     * {@link Locked}는 repeatable이므로 여러 개가 반환될 수 있다.
     *
     * @param method 락 선언을 확인할 메서드
     * @return 선언된 락 애노테이션 목록
     */
    private List<Locked> findLockAnnotationList(Method method) {
        if (method == null) {
            return List.of();
        }

        Locked[] annotations = method.getAnnotationsByType(Locked.class);

        if (annotations == null || annotations.length == 0) {
            return List.of();
        }

        return Arrays.asList(annotations);
    }

    /**
     * 애노테이션 선언값과 메서드 인자를 조합해 최종 락 타겟 목록을 생성한다.
     *
     * <p>중복 키(type+id)는 한 번만 유지해 불필요한 중복 획득을 방지한다.</p>
     *
     * @param lockAnnotations 메서드에 선언된 락 설정 목록
     * @param args 메서드 호출 인자
     * @return 실제 획득 대상 락 목록
     */
    private List<LockTarget> getLockTargetList(List<Locked> lockAnnotations, Object[] args) {
        if (args == null || lockAnnotations == null || lockAnnotations.isEmpty()) {
            return List.of();
        }

        List<LockTarget> lockTargets = new ArrayList<>(lockAnnotations.size());
        Set<String> seenTargetKeys = new HashSet<>(lockAnnotations.size());

        for (Locked annotation : lockAnnotations) {
            String type = normalizeType(annotation.type());
            String id = extractId(args, annotation.idIndex());

            if (id == null) {
                continue;
            }

            String targetKey = lockService.createKey(type, id);
            if (!seenTargetKeys.add(targetKey)) {
                continue;
            }

            lockTargets.add(new LockTarget(type, id, annotation.retry()));
        }

        return lockTargets;
    }

    /**
     * 애노테이션 선언 순서 기준으로 락을 순차 획득한다.
     *
     * <p>중간 획득에 실패하면, 그 시점까지 이미 획득한 락을 역순으로 즉시 해제해
     * 부분 획득 상태가 남지 않도록 보장한다.</p>
     *
     * @param lockTargets 획득 대상 목록
     * @return 획득된 락 핸들 목록
     */
    private List<LockHandle> acquireLocks(List<LockTarget> lockTargets) {
        List<LockHandle> handles = new ArrayList<>(lockTargets.size());
        try {
            for (LockTarget target : lockTargets) {
                handles.add(
                        lockService.acquire(target.type(), target.id(), target.retry())
                );
            }
            return handles;
        } catch (RuntimeException error) {
            releaseLocks(handles);
            throw error;
        }
    }

    /**
     * 락을 역순으로 해제한다.
     * 획득 순서와 짝을 맞춰 락 생명주기 추적을 유지한다.
     *
     * @param handles 획득된 락 핸들 목록
     */
    private void releaseLocks(List<LockHandle> handles) {
        for (int i = handles.size() - 1; i >= 0; i--) {
            lockService.release(handles.get(i));
        }
    }

    /**
     * 메서드 인자에서 락 ID를 추출한다.
     *
     * <p>현재 지원 타입은 {@code Integer}, {@code Long}, {@code String}이며,
     * 빈 문자열/null은 "락 타겟 없음"으로 간주해 null을 반환한다.</p>
     * <p>idIndex 범위를 벗어나거나 지원하지 않는 타입이면 설정 오류로 보고 예외를 던진다.</p>
     *
     * @param args 메서드 인자 배열
     * @param index ID 추출 대상 인자 위치(0-based)
     * @return 정규화된 ID 문자열 또는 null
     * @throws LockAcquireException idIndex 범위 오류 또는 지원하지 않는 타입일 때
     */
    private String extractId(Object[] args, int index) {
        if (index < 0 || index >= args.length) {
            throw new LockAcquireException(LOCK_ID_INDEX_FAILED);
        }

        Object value = args[index];

        if (value == null) {
            return null;
        }

        if (value instanceof Integer intValue) {
            return String.valueOf(intValue);
        }

        if (value instanceof Long longValue) {
            return String.valueOf(longValue);
        }

        if (value instanceof String str) {
            String trimmed = str.trim();
            return trimmed.isBlank() ? null : trimmed;
        }

        throw new LockAcquireException(LOCK_ID_TYPE_UNSUPPORTED);
    }

    /**
     * 락 타입을 대문자로 정규화하고 null/blank를 검증한다.
     *
     * @param type 애노테이션에 선언된 타입 문자열
     * @return 공백 제거 + 대문자 정규화된 타입
     * @throws LockAcquireException type이 null/blank일 때
     */
    public String normalizeType(String type) {
        if (type == null) {
            throw new LockAcquireException(LOCK_TYPE_FAILED);
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new LockAcquireException(LOCK_TYPE_FAILED);
        }
        return normalized;
    }

    /**
     * 락 타입/ID/실패 정책을 함께 보관하는 내부 전송 객체.
     */
    private record LockTarget(String type, String id, boolean retry) {
    }
}
