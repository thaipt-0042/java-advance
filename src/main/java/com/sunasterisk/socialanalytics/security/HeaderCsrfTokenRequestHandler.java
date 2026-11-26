package com.sunasterisk.socialanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * CSRF token of the API: only from the {@code X-XSRF-TOKEN} header, raw (the value of the {@code XSRF-TOKEN}
 * cookie). Never from a {@code _csrf} request parameter: looking for one makes Tomcat parse a multipart body
 * inside the security filter, where a body over the upload limit throws before any error handler can answer
 * (an ERROR log with a stack trace for every such request, which anyone could send).
 */
class HeaderCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public @Nullable String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
