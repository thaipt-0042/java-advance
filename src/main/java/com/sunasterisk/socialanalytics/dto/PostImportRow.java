package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelColumn;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;
import org.hibernate.validator.constraints.URL;

/**
 * One row of the post import file. Limits are the same as {@link PostCreateRequest}. The four metric columns are
 * optional, but a row with one of them must have all four: it then creates a snapshot.
 */
public record PostImportRow(
        @ExcelColumn(name = "platform", required = true) @NotNull SocialPlatform platform,
        @ExcelColumn(name = "externalId", required = true) @NotBlank @Size(max = Post.EXTERNAL_ID_MAX_LENGTH)
        String externalId,
        @ExcelColumn(name = "accountName", required = true) @NotBlank @Size(max = Post.ACCOUNT_NAME_MAX_LENGTH)
        String accountName,
        @ExcelColumn(name = "url") @Size(max = Post.URL_MAX_LENGTH) @URL(regexp = Post.URL_PATTERN) String url,
        @ExcelColumn(name = "content") @Size(max = Post.CONTENT_MAX_LENGTH) String content,
        @ExcelColumn(name = "publishedAt") Instant publishedAt,
        @ExcelColumn(name = "likes") @PositiveOrZero Long likes,
        @ExcelColumn(name = "shares") @PositiveOrZero Long shares,
        @ExcelColumn(name = "comments") @PositiveOrZero Long comments,
        @ExcelColumn(name = "followers") @PositiveOrZero Long followers) {

    @AssertTrue(message = "likes, shares, comments, followers phải có đủ cả bốn hoặc để trống cả bốn")
    public boolean isMetricsAllOrNone() {
        long present = metrics().filter(Objects::nonNull).count();
        return present == 0 || present == 4;
    }

    public boolean hasMetrics() {
        return metrics().allMatch(Objects::nonNull);
    }

    private Stream<Long> metrics() {
        return Stream.of(likes, shares, comments, followers);
    }
}
