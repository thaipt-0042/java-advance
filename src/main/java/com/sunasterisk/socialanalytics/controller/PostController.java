package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.dto.PostCreateRequest;
import com.sunasterisk.socialanalytics.dto.PostFilter;
import com.sunasterisk.socialanalytics.dto.PostResponse;
import com.sunasterisk.socialanalytics.dto.PostUpdateRequest;
import com.sunasterisk.socialanalytics.service.PostService;
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
@RequestMapping("/api/v1/posts")
@Tag(name = "Posts", description = "Bài viết được theo dõi trên Facebook / Twitter")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    @Operation(summary = "Danh sách bài viết",
            description = "Phân trang, mới tạo trước. Lọc theo nền tảng và từ khóa (nội dung hoặc tên tài khoản). "
                    + "Trang vượt quá số trang trả về `content` rỗng.")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "422", description = "Tham số không hợp lệ")
    public PageResponse<PostResponse> list(@ParameterObject @Valid PostFilter filter,
            @ParameterObject @Valid PageQuery pageQuery) {
        return postService.search(filter, pageQuery);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết bài viết")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "Không có bài viết với id này")
    public PostResponse get(@PathVariable Long id) {
        return postService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm bài viết cần theo dõi")
    @ApiResponse(responseCode = "201", description = "Đã tạo; header `Location` trỏ tới bài viết")
    @ApiResponse(responseCode = "409", description = "Đã có bài viết cùng nền tảng và externalId")
    @ApiResponse(responseCode = "422", description = "Dữ liệu không hợp lệ")
    public ResponseEntity<PostResponse> create(@Valid @RequestBody PostCreateRequest request) {
        PostResponse created = postService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa bài viết",
            description = "Thay toàn bộ các trường sửa được. `platform` và `externalId` là định danh, không sửa được. "
                    + "`updatedAt` là giá trị client đã đọc.")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "Không có bài viết với id này")
    @ApiResponse(responseCode = "409", description = "Bài viết đã bị sửa sau khi client đọc (`updatedAt` khác)")
    @ApiResponse(responseCode = "422", description = "Dữ liệu không hợp lệ")
    public PostResponse update(@PathVariable Long id, @Valid @RequestBody PostUpdateRequest request) {
        return postService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa bài viết", description = "Xóa luôn mọi snapshot số liệu của bài viết.")
    @ApiResponse(responseCode = "204", description = "Đã xóa")
    @ApiResponse(responseCode = "404", description = "Không có bài viết với id này")
    public void delete(@PathVariable Long id) {
        postService.delete(id);
    }
}
