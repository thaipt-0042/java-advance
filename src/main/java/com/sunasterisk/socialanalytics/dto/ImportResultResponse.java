package com.sunasterisk.socialanalytics.dto;

import com.sunasterisk.socialanalytics.entity.ImportBatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ImportResultResponse(
        @Schema(description = "Id của lần import (bảng import_batches)") Long batchId,
        @Schema(description = "Số dòng dữ liệu trong file (bỏ qua dòng trống)") int totalRows,
        @Schema(description = "Bài viết mới") int createdPosts,
        @Schema(description = "Bài viết đã có và có dữ liệu thay đổi; dòng giống hệt dữ liệu đang lưu không tính")
        int updatedPosts,
        @Schema(description = "Snapshot số liệu được tạo (dòng có đủ bốn cột số liệu)") int snapshotsCreated,
        @Schema(description = "capturedAt chung của mọi snapshot lần này; null khi không có snapshot")
        Instant capturedAt) {

    public static ImportResultResponse from(ImportBatch batch) {
        return new ImportResultResponse(batch.getId(), batch.getTotalRows(), batch.getCreatedPosts(),
                batch.getUpdatedPosts(), batch.getSnapshotsCreated(), batch.getCapturedAt());
    }
}
