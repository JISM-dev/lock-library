package com.library.lock.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Locked}를 반복 선언하기 위한 컨테이너 애노테이션.
 *
 * <p>일반적으로 직접 사용하지 않고, 동일 메서드에 {@link Locked}를 여러 번 선언하면
 * 컴파일러가 내부적으로 이 컨테이너 형태로 변환한다.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LockList {

    /**
     * 메서드에 적용된 락 선언 목록.
     *
     * @return 반복 선언된 {@link Locked} 배열
     */
    Locked[] value();
}
