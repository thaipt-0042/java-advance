package com.sunasterisk.socialanalytics.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unexpected errors of the pages: the error page, not the JSON error of the API. Ordered before
 * {@code GlobalExceptionHandler}, and limited to this package, so the API keeps its format.
 */
@ControllerAdvice(basePackageClasses = WebExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    String handleUnexpected(Exception ex, HttpServletResponse response) {
        log.error("Unhandled exception on a page", ex);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return "error";
    }
}
