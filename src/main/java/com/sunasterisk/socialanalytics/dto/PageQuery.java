package com.sunasterisk.socialanalytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Paging query parameters. Sorting is fixed by each endpoint (no {@code sort} parameter, so an unknown property
 * can't reach the query). A page past the last one returns empty {@code content}. {@link #MAX_PAGE} keeps the
 * offset within the int range that JPA accepts.
 */
public record PageQuery(
        @Schema(description = "Số trang, bắt đầu từ 0", defaultValue = "0")
        @Min(0) @Max(MAX_PAGE) Integer page,
        @Schema(description = "Số phần tử mỗi trang", defaultValue = "20")
        @Min(1) @Max(MAX_SIZE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** {@code MAX_PAGE * MAX_SIZE} must stay below {@link Integer#MAX_VALUE}. */
    public static final int MAX_PAGE = 10_000_000;

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size, sort);
    }
}
