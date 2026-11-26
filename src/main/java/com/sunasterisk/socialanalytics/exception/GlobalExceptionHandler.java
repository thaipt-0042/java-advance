package com.sunasterisk.socialanalytics.exception;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error format for every API error: RFC 9457 {@link ProblemDetail} plus a {@code code} property
 * (and {@code errors} for validation).
 *
 * <ul>
 *   <li>Framework errors (400 bad JSON / wrong type, 404 unknown path, 405, 415, ...) keep their status through
 *       {@link ResponseEntityExceptionHandler}; {@code code} is the status name, e.g. {@code METHOD_NOT_ALLOWED}.</li>
 *   <li>Bean Validation failures: 422 {@code VALIDATION}, {@code errors} as {@code [{field, message}]}.</li>
 *   <li>{@link RowValidationException} (rows of an uploaded file): 422 {@code VALIDATION}, {@code errors} as
 *       {@code [{row, column, message}]}.</li>
 *   <li>{@link UnprocessableException} (a valid request the app refuses, e.g. an export too large): 422
 *       {@code VALIDATION}, empty {@code errors}.</li>
 *   <li>{@link InvalidFileException}: 400 {@code BAD_REQUEST}.</li>
 *   <li>Not logged in ({@link AuthenticationException}): 401 {@code UNAUTHORIZED}. Logged in without the role, or a
 *       missing / wrong CSRF token ({@link AccessDeniedException}): 403 {@code FORBIDDEN}. Both are raised by the
 *       security filters, which hand them to this class ({@code SecurityConfig}).</li>
 *   <li>{@link ResourceNotFoundException}: 404 {@code NOT_FOUND}.</li>
 *   <li>{@link ConflictException} and {@link DataIntegrityViolationException}: 409 {@code CONFLICT}.</li>
 *   <li>Anything else: 500 {@code INTERNAL_ERROR}, details only in the log.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String CODE = "code";

    /** Bean Validation on a request body or a {@code @ParameterObject} (handled by the base class, so override). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION", "Dữ liệu không hợp lệ");
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(RowValidationException.class)
    ResponseEntity<Object> handleRowValidation(RowValidationException ex, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION", ex.getMessage());
        body.setProperty("errors", ex.getErrors());
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(UnprocessableException.class)
    ResponseEntity<Object> handleUnprocessable(UnprocessableException ex, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION", ex.getMessage());
        body.setProperty("errors", List.of());
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(InvalidFileException.class)
    ResponseEntity<Object> handleInvalidFile(InvalidFileException ex, WebRequest request) {
        return respond(ex, HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException ex, WebRequest request) {
        return respond(ex, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        String detail = ex instanceof CsrfException
                ? "Thiếu hoặc sai CSRF token: gửi lại giá trị cookie XSRF-TOKEN trong header X-XSRF-TOKEN"
                : "Không có quyền thực hiện thao tác này";
        return respond(ex, HttpStatus.FORBIDDEN, "FORBIDDEN", detail, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return respond(ex, HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return respond(ex, HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    /**
     * A database constraint rejected the write, typically two concurrent requests that both passed the
     * duplicate check. Logged because it may also reveal a missing check (a real bug).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation", ex);
        return respond(ex, HttpStatus.CONFLICT, "CONFLICT",
                "Dữ liệu xung đột với dữ liệu hiện có, hãy tải lại rồi thử lại", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(ex, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Đã có lỗi xảy ra", request);
    }

    /** Adds {@code code} to the ProblemDetail of framework errors so every error has the same shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(CODE))) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty(CODE, status != null ? status.name() : "ERROR");
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(Exception ex, HttpStatus status, String code, String detail,
            WebRequest request) {
        return handleExceptionInternal(ex, problem(status, code, detail), new HttpHeaders(), status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty(CODE, code);
        return body;
    }

    private static Map<String, String> toFieldError(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            // A value that can't be converted (e.g. platform=INSTAGRAM in the query string): hide the stack-like text.
            String message = fieldError.isBindingFailure() ? "Giá trị không hợp lệ" : fieldError.getDefaultMessage();
            return Map.of("field", fieldError.getField(), "message", String.valueOf(message));
        }
        return Map.of("field", error.getObjectName(), "message", String.valueOf(error.getDefaultMessage()));
    }
}
