package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.dto.PostCreateRequest;
import com.sunasterisk.socialanalytics.dto.PostFilter;
import com.sunasterisk.socialanalytics.dto.PostResponse;
import com.sunasterisk.socialanalytics.dto.PostUpdateRequest;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.util.DbTime;
import com.sunasterisk.socialanalytics.util.LikePatterns;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRUD for tracked posts. Entities are mapped to DTOs inside the transaction (open-in-view is off).
 *
 * <p>A duplicate {@code (platform, externalId)} is checked first to return a precise message; two concurrent
 * requests can both pass that check, then the UNIQUE constraint rejects the second one
 * ({@code DataIntegrityViolationException}, also 409 in {@code GlobalExceptionHandler}).
 *
 * <p>Update locks the row on its first read and delete is a single {@code DELETE} checked by row count, so a post
 * deleted concurrently gives 404 rather than a 500 from Hibernate's "unexpected row count" check.
 */
@Service
@Transactional(readOnly = true)
public class PostService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

    private final PostRepository postRepository;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    public PageResponse<PostResponse> search(PostFilter filter, PageQuery pageQuery) {
        String pattern = StringUtils.hasText(filter.q()) ? LikePatterns.contains(filter.q().strip()) : null;
        return PageResponse.of(postRepository
                .search(filter.platform(), pattern, pageQuery.toPageable(NEWEST_FIRST))
                .map(PostResponse::from));
    }

    public PostResponse get(Long id) {
        return PostResponse.from(find(id));
    }

    @Transactional
    public PostResponse create(PostCreateRequest request) {
        String externalId = request.externalId().strip();
        if (postRepository.existsByPlatformAndExternalId(request.platform(), externalId)) {
            throw new ConflictException(
                    "Bài viết %s có externalId = %s đã tồn tại".formatted(request.platform(), externalId));
        }
        Post post = new Post(request.platform(), externalId, request.accountName().strip());
        post.setUrl(blankToNull(request.url()));
        post.setContent(blankToNull(request.content()));
        post.setPublishedAt(DbTime.toColumnPrecision(request.publishedAt()));
        return PostResponse.from(postRepository.save(post));
    }

    @Transactional
    public PostResponse update(Long id, PostUpdateRequest request) {
        Post post = postRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        post.setAccountName(request.accountName().strip());
        post.setUrl(blankToNull(request.url()));
        post.setContent(blankToNull(request.content()));
        post.setPublishedAt(DbTime.toColumnPrecision(request.publishedAt()));
        // Flush now so updatedAt in the response is the new value.
        return PostResponse.from(postRepository.saveAndFlush(post));
    }

    /** Deletes the post; its metrics are removed by the database ({@code ON DELETE CASCADE}). */
    @Transactional
    public void delete(Long id) {
        if (postRepository.deleteByIdReturningCount(id) == 0) {
            throw notFound(id);
        }
    }

    private Post find(Long id) {
        return postRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("bài viết", id);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
