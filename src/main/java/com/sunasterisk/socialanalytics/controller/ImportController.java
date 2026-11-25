package com.sunasterisk.socialanalytics.controller;

import com.sunasterisk.socialanalytics.dto.ImportResultResponse;
import com.sunasterisk.socialanalytics.entity.ImportBatch;
import com.sunasterisk.socialanalytics.exception.InvalidFileException;
import com.sunasterisk.socialanalytics.service.PostImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/import-posts")
@Tag(name = "Import", description = "Nhập bài viết từ file Excel")
public class ImportController {

    private final PostImportService importService;

    public ImportController(PostImportService importService) {
        this.importService = importService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import bài viết từ file .xlsx",
            description = """
                    Sheet đầu tiên, dòng 1 là tiêu đề. Cột bắt buộc: `platform`, `externalId`, `accountName`; \
                    tùy chọn: `url`, `content`, `publishedAt`, `likes`, `shares`, `comments`, `followers` \
                    (có một cột số liệu thì phải có đủ bốn). Tối đa 5MB, 5.000 dòng. Có dòng lỗi thì không lưu \
                    gì; bài đã có (cùng nền tảng và externalId) được cập nhật theo file.""")
    @ApiResponse(responseCode = "200", description = "Đã import")
    @ApiResponse(responseCode = "400", description = "Không phải file .xlsx đọc được, thiếu hoặc sai tên cột, "
            + "không có dòng dữ liệu")
    @ApiResponse(responseCode = "413", description = "File lớn hơn 5MB")
    @ApiResponse(responseCode = "409", description = "Một bài mới trong file vừa được tạo bởi request khác")
    @ApiResponse(responseCode = "422", description = "Có dòng lỗi (`errors` theo số dòng Excel) hoặc quá 5.000 dòng")
    public ImportResultResponse importPosts(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("File rỗng");
        }
        try (InputStream in = file.getInputStream()) {
            return importService.importPosts(displayName(file), in);
        } catch (IOException ex) {
            throw new InvalidFileException("Không đọc được file tải lên", ex);
        }
    }

    /** Original name without any path, cut to the column length; for display only. */
    private static String displayName(MultipartFile file) {
        String original = file.getOriginalFilename();
        // Some browsers send the full client path, possibly with Windows separators.
        String name = original == null ? null : StringUtils.getFilename(original.replace('\\', '/'));
        if (!StringUtils.hasText(name)) {
            return "(không tên)";
        }
        name = name.strip();
        return name.length() > ImportBatch.FILE_NAME_MAX_LENGTH
                ? name.substring(0, ImportBatch.FILE_NAME_MAX_LENGTH) : name;
    }
}
