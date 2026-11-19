package com.sunasterisk.socialanalytics.exception;

/** An id (in the path, the query string or the request body) that does not exist. Mapped to 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("Không tìm thấy %s có id = %s".formatted(resource, id));
    }
}
