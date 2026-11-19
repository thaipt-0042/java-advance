package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.MetricCreateRequest;
import com.sunasterisk.socialanalytics.dto.MetricFilter;
import com.sunasterisk.socialanalytics.dto.MetricResponse;
import com.sunasterisk.socialanalytics.dto.MetricUpdateRequest;
import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.service.SocialMetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Snapshot likes / shares / comments / followers của bài viết theo thời gian")
public class SocialMetricController {

    private final SocialMetricService metricService;

    public SocialMetricController(SocialMetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    @Operation(summary = "Danh sách snapshot",
            description = "Phân trang, mới đo trước. Lọc theo bài viết và khoảng thời gian `[from, to)`.")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "`postId` không tồn tại")
    @ApiResponse(responseCode = "422", description = "Tham số không hợp lệ (ví dụ `from` không trước `to`)")
    public PageResponse<MetricResponse> list(@ParameterObject @Valid MetricFilter filter,
            @ParameterObject @Valid PageQuery pageQuery) {
        return metricService.search(filter, pageQuery);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết snapshot")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "Không có snapshot với id này")
    public MetricResponse get(@PathVariable Long id) {
        return metricService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm snapshot cho bài viết")
    @ApiResponse(responseCode = "201", description = "Đã tạo; header `Location` trỏ tới snapshot")
    @ApiResponse(responseCode = "404", description = "`postId` không tồn tại")
    @ApiResponse(responseCode = "409", description = "Bài viết đã có snapshot tại `capturedAt`")
    @ApiResponse(responseCode = "422", description = "Dữ liệu không hợp lệ (ví dụ số âm)")
    public ResponseEntity<MetricResponse> create(@Valid @RequestBody MetricCreateRequest request) {
        MetricResponse created = metricService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa snapshot",
            description = "Thay thời điểm đo và các số đếm. Không chuyển snapshot sang bài viết khác được.")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "Không có snapshot với id này")
    @ApiResponse(responseCode = "409", description = "Bài viết đã có snapshot khác tại `capturedAt`")
    @ApiResponse(responseCode = "422", description = "Dữ liệu không hợp lệ")
    public MetricResponse update(@PathVariable Long id, @Valid @RequestBody MetricUpdateRequest request) {
        return metricService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa snapshot")
    @ApiResponse(responseCode = "204", description = "Đã xóa")
    @ApiResponse(responseCode = "404", description = "Không có snapshot với id này")
    public void delete(@PathVariable Long id) {
        metricService.delete(id);
    }
}
