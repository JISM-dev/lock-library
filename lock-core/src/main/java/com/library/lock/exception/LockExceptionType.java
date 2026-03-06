package com.library.lock.exception;

/**
 * 락 라이브러리 전용 예외 타입.
 *
 * <p>애플리케이션 전역 {@code ExceptionType}와 분리해, 라이브러리 단독 배포 시에도
 * 예외 코드/메시지 체계를 독립적으로 유지할 수 있도록 한다.</p>
 */
public enum LockExceptionType {

    /**
     * 잠시 후 재시도 가능한 경합 상황.
     */
    AGAIN_REQUEST_PLEASE(20000, "잠시 후 다시 시도해주세요"),
    /**
     * 즉시 실패로 처리할 락 획득 실패.
     */
    LOCK_ACQUIRE_FAILED(20001, "요청이 락에걸려 실패했어요."),
    /**
     * 락 ID 값이 비어 있거나 유효하지 않은 경우.
     */
    LOCK_ID_FAILED(20002, "락 아이디 정보를 입력해주세요."),
    /**
     * 락 타입 값이 비어 있거나 유효하지 않은 경우.
     */
    LOCK_TYPE_FAILED(20003, "락 타입 정보를 입력해주세요."),
    /**
     * 락 ID 인덱스 설정이 인자 범위를 벗어난 경우.
     */
    LOCK_ID_INDEX_FAILED(20004, "락 아이디 인덱스 설정이 올바르지 않아요."),
    /**
     * 현재 지원하지 않는 ID 타입으로 요청한 경우.
     */
    LOCK_ID_TYPE_UNSUPPORTED(20005, "락 아이디 타입이 지원되지 않아요.");

    private final int code;
    private final String errorMessage;

    LockExceptionType(int code, String errorMessage) {
        this.code = code;
        this.errorMessage = errorMessage;
    }

    /**
     * 클라이언트 응답용 코드값.
     *
     * @return 에러 코드
     */
    public int getCode() {
        return code;
    }

    /**
     * 클라이언트 응답용 메시지값.
     *
     * @return 에러 메시지
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
