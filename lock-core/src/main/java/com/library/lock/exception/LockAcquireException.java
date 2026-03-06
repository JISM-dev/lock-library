package com.library.lock.exception;

/**
 * 락 획득/검증 과정에서 발생하는 도메인 예외.
 *
 * <p>API 응답 레이어에서는 이 예외를 잡아 코드/메시지/타입을 그대로 내려줄 수 있도록
 * {@link LockExceptionType} 정보를 필드로 보관한다.</p>
 */
public class LockAcquireException extends RuntimeException {

    /**
     * 서비스 공통 예외 타입.
     */
    private final LockExceptionType type;
    /**
     * 클라이언트 응답용 에러 코드.
     */
    private final int code;
    /**
     * 클라이언트 응답용 에러 메시지.
     */
    private final String errorMessage;

    /**
     * 예외 타입을 기준으로 코드/메시지를 고정한다.
     *
     * @param type 락 실패 원인에 해당하는 예외 타입
     */
    public LockAcquireException(LockExceptionType type) {
        this.type = type;
        this.code = type.getCode();
        this.errorMessage = type.getErrorMessage();
    }

    /**
     * 락 실패 세부 유형을 반환한다.
     *
     * @return 실패 원인에 해당하는 {@link LockExceptionType}
     */
    public LockExceptionType getType() {
        return type;
    }

    /**
     * API 응답용 에러 코드를 반환한다.
     *
     * @return 에러 코드
     */
    public int getCode() {
        return code;
    }

    /**
     * API 응답용 에러 메시지를 반환한다.
     *
     * @return 에러 메시지
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
