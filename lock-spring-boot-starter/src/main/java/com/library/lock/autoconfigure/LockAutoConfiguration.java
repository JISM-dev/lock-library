package com.library.lock.autoconfigure;

import com.library.lock.aop.LockAspect;
import com.library.lock.service.LockService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 락 라이브러리 스타터 자동 설정.
 *
 * <p>소비 서비스에서 별도 설정 클래스를 만들지 않아도
 * {@link LockService}, {@link LockAspect} 빈이 자동 등록되도록 한다.</p>
 *
 * <p>기본 동작은 활성화이며, {@code library.lock.enabled=false}로 비활성화할 수 있다.</p>
 */
@AutoConfiguration
// AOP 관련 클래스가 없으면 자동 설정 자체를 활성화하지 않는다.
@ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
// 서비스별로 on/off 제어가 가능하며, 설정이 없으면 기본 활성화한다.
@ConditionalOnProperty(
        prefix = "library.lock",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LockAutoConfiguration {

    /**
     * 인메모리 락 저장소 역할의 기본 빈.
     *
     * <p>소비 서비스에서 같은 타입의 빈을 직접 등록하면 이 기본 빈은 생성하지 않는다.</p>
     *
     * @return 락 획득/해제를 담당하는 기본 {@link LockService}
     */
    @Bean
    @ConditionalOnMissingBean
    public LockService lockService() {
        return new LockService();
    }

    /**
     * {@code @Locked}를 해석해 락 획득/해제를 수행하는 AOP 빈.
     *
     * <p>소비 서비스에서 커스텀 Aspect를 등록하면 이 기본 빈은 생성하지 않는다.</p>
     *
     * @param lockService 락 저장소 빈
     * @return 메서드 실행 전후 락을 적용하는 {@link LockAspect}
     */
    @Bean
    @ConditionalOnMissingBean
    public LockAspect lockAspect(LockService lockService) {
        return new LockAspect(lockService);
    }
}
