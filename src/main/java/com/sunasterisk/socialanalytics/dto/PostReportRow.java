package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelColumn;
import java.time.Instant;

/**
 * One row of the "Posts" sheet of the report: a post and its latest snapshot (metric columns empty when the post
 * has none). Column names are those of the import file, plus {@code capturedAt}.
 */
public record PostReportRow(
        @ExcelColumn(name = "platform") SocialPlatform platform,
        @ExcelColumn(name = "externalId") String externalId,
        @ExcelColumn(name = "accountName") String accountName,
        @ExcelColumn(name = "url") String url,
        @ExcelColumn(name = "content") String content,
        @ExcelColumn(name = "publishedAt") Instant publishedAt,
        @ExcelColumn(name = "likes") Long likes,
        @ExcelColumn(name = "shares") Long shares,
        @ExcelColumn(name = "comments") Long comments,
        @ExcelColumn(name = "followers") Long followers,
        @ExcelColumn(name = "capturedAt") Instant capturedAt) {
}
