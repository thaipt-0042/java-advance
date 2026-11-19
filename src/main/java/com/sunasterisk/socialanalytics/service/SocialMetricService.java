package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.MetricCreateRequest;
import com.sunasterisk.socialanalytics.dto.MetricFilter;
import com.sunasterisk.socialanalytics.dto.MetricResponse;
import com.sunasterisk.socialanalytics.dto.MetricUpdateRequest;
import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import com.sunasterisk.socialanalytics.util.DbTime;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for metric snapshots. A post has at most one snapshot per {@code capturedAt}: checked first for a precise
 * message, enforced by the UNIQUE constraint under concurrency (409 either way).
 */
@Service
@Transactional(readOnly = true)
public class SocialMetricService {

    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "capturedAt", "id");

    private final SocialMetricRepository metricRepository;
    private final PostRepository postRepository;

    public SocialMetricService(SocialMetricRepository metricRepository, PostRepository postRepository) {
        this.metricRepository = metricRepository;
        this.postRepository = postRepository;
    }

    public PageResponse<MetricResponse> search(MetricFilter filter, PageQuery pageQuery) {
        if (filter.postId() != null && !postRepository.existsById(filter.postId())) {
            throw postNotFound(filter.postId());
        }
        return PageResponse.of(metricRepository
                .search(filter.postId(), filter.from(), filter.to(), pageQuery.toPageable(LATEST_FIRST))
                .map(MetricResponse::from));
    }

    public MetricResponse get(Long id) {
        return MetricResponse.from(find(id));
    }

    @Transactional
    public MetricResponse create(MetricCreateRequest request) {
        Post post = postRepository.findById(request.postId())
                .orElseThrow(() -> postNotFound(request.postId()));
        Instant capturedAt = DbTime.toColumnPrecision(request.capturedAt());
        if (metricRepository.existsByPostIdAndCapturedAt(post.getId(), capturedAt)) {
            throw duplicateSnapshot(post.getId(), capturedAt);
        }
        SocialMetric metric = new SocialMetric(post, capturedAt);
        metric.setLikes(request.likes());
        metric.setShares(request.shares());
        metric.setComments(request.comments());
        metric.setFollowers(request.followers());
        return MetricResponse.from(metricRepository.save(metric));
    }

    @Transactional
    public MetricResponse update(Long id, MetricUpdateRequest request) {
        SocialMetric metric = find(id);
        Long postId = metric.getPost().getId();
        Instant capturedAt = DbTime.toColumnPrecision(request.capturedAt());
        if (metricRepository.existsByPostIdAndCapturedAtAndIdNot(postId, capturedAt, id)) {
            throw duplicateSnapshot(postId, capturedAt);
        }
        metric.setCapturedAt(capturedAt);
        metric.setLikes(request.likes());
        metric.setShares(request.shares());
        metric.setComments(request.comments());
        metric.setFollowers(request.followers());
        // Flush now: a concurrent duplicate fails here (409) and updatedAt in the response is the new value.
        return MetricResponse.from(metricRepository.saveAndFlush(metric));
    }

    @Transactional
    public void delete(Long id) {
        metricRepository.delete(find(id));
    }

    private SocialMetric find(Long id) {
        return metricRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("snapshot", id));
    }

    private static ResourceNotFoundException postNotFound(Long postId) {
        return new ResourceNotFoundException("bài viết", postId);
    }

    private static ConflictException duplicateSnapshot(Long postId, Instant capturedAt) {
        return new ConflictException(
                "Bài viết id = %d đã có snapshot tại %s".formatted(postId, capturedAt));
    }
}
