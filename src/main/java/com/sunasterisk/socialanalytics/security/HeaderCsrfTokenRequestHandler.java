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
 *
 * <p>The token is loaded on every request, so the {@code XSRF-TOKEN} cookie is always set: without a request
 * attribute name the handler reads the token's parameter name, which loads the deferred token, as
 * {@code csrf.spa()} does for the web chain. Otherwise the cookie would only be written when something reads the
 * token, and a JavaScript client right after login would have no token to send.
 */
class HeaderCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    HeaderCsrfTokenRequestHandler() {
        setCsrfRequestAttributeName(null);
    }

    @Override
    public @Nullable String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
