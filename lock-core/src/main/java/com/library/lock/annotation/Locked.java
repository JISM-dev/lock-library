package com.library.lock.annotation;

import com.library.lock.aop.LockAspect;
import com.library.lock.service.LockService;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Target;

/**
 * 메서드 실행 전 특정 자원에 대한 메모리 락을 획득하도록 선언하는 애노테이션.
 *
 * <p>동일 메서드에 여러 개를 선언할 수 있으며, 선언 순서대로 락을 획득한 뒤
 * 메서드 종료 시 역순으로 해제된다. (LIFO)</p>
 *
 * <p>실제 락 처리 로직은 {@link LockAspect}와 {@link LockService}가 담당한다.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(LockList.class)
public @interface Locked {

    /**
     * 락을 구분하는 논리 타입.
     *
     * <p>예: {@code TEAM}, {@code MEMBER}, {@code MATCH_POST}</p>
     * <p>타입과 id가 결합되어 최종 락 키를 만든다.</p>
     *
     * @return 락 대상의 논리 타입 문자열
     */
    String type();

    /**
     * 락 ID를 추출할 메서드 인자 위치(0-based).
     *
     * <p>예: {@code idIndex=1}이면 두 번째 인자를 락 ID로 사용한다.</p>
     * <p>지원 타입은 현재 {@code Integer}, {@code Long}, {@code String}이다.</p>
     *
     * @return 락 ID를 추출할 인자 인덱스(0-based)
     */
    int idIndex();

    /**
     * 락 획득 실패를 재시도 가능한 실패로 볼지 여부.
     *
     * <p>{@code true}: "잠시 후 다시 시도" 성격의 예외를 던진다.</p>
     * <p>{@code false}: 즉시 실패 성격의 예외를 던진다.</p>
     *
     * @return 재시도 유도 실패로 처리할지 여부
     */
    boolean retry() default true;
}
