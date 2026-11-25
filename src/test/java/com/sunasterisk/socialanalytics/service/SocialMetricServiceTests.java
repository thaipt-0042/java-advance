package com.sunasterisk.socialanalytics.service;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.dto.MetricCreateRequest;
import com.sunasterisk.socialanalytics.dto.MetricFilter;
import com.sunasterisk.socialanalytics.dto.MetricResponse;
import com.sunasterisk.socialanalytics.dto.MetricUpdateRequest;
import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialMetric;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.SocialMetricRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class SocialMetricServiceTests {

    private static final Instant CREATED = Instant.parse("2026-03-01T03:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-03-02T04:00:00Z");
    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-03-08T00:00:00Z");

    @Mock
    private SocialMetricRepository metricRepository;

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private SocialMetricService metricService;

    private final Post post = persisted(new Post(SocialPlatform.TWITTER, "ext-5", "sun_asterisk"), 5L, CREATED,
            CREATED);

    @Test
    void searchByPostChecksPostAndUsesLatestFirstSort() {
        when(postRepository.existsById(5L)).thenReturn(true);
        when(metricRepository.search(eq(5L), eq(FROM), eq(TO), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(sampleMetric(11L)), inv.getArgument(3), 1));

        PageResponse<MetricResponse> page = metricService.search(new MetricFilter(5L, FROM, TO), new PageQuery(0, 10));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(metricRepository).search(eq(5L), eq(FROM), eq(TO), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "capturedAt", "id"));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.content()).extracting(MetricResponse::id).containsExactly(11L);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void searchByUnknownPostThrowsNotFoundWithoutQuerying() {
        when(postRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> metricService.search(new MetricFilter(99L, null, null), new PageQuery(null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy bài viết có id = 99");
        verifyNoInteractions(metricRepository);
    }

    @Test
    void searchWithoutPostSkipsPostCheck() {
        when(metricRepository.search(isNull(), isNull(), isNull(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(3), 0));

        metricService.search(new MetricFilter(null, null, null), new PageQuery(null, null));

        verify(postRepository, never()).existsById(any());
    }

    @Test
    void getMapsEveryField() {
        when(metricRepository.findById(11L)).thenReturn(Optional.of(sampleMetric(11L)));

        assertThat(metricService.get(11L)).isEqualTo(
                new MetricResponse(11L, 5L, 120, 15, 8, 5400, Instant.parse("2026-03-01T01:00:00Z"), CREATED,
                        UPDATED));
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        when(metricRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricService.get(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy snapshot có id = 99");
    }

    @Test
    void createLocksPostAndSavesTruncatedCaptureTime() {
        Instant truncated = Instant.parse("2026-03-01T01:00:00.123456Z");
        when(postRepository.findByIdForShare(5L)).thenReturn(Optional.of(post));
        when(metricRepository.existsByPostIdAndCapturedAt(5L, truncated)).thenReturn(false);
        when(metricRepository.save(any(SocialMetric.class)))
                .thenAnswer(inv -> persisted(inv.<SocialMetric>getArgument(0), 12L, CREATED, CREATED));

        MetricResponse response = metricService.create(
                new MetricCreateRequest(5L, Instant.parse("2026-03-01T01:00:00.123456789Z"), 1L, 2L, 3L, 4L));

        assertThat(response).isEqualTo(new MetricResponse(12L, 5L, 1, 2, 3, 4, truncated, CREATED, CREATED));
        verify(postRepository, never()).findById(anyLong());
    }

    @Test
    void createForUnknownPostThrowsNotFoundWithoutSaving() {
        when(postRepository.findByIdForShare(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricService.create(new MetricCreateRequest(99L, FROM, 0L, 0L, 0L, 0L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy bài viết có id = 99");
        verify(metricRepository, never()).save(any());
    }

    @Test
    void createDuplicateTimeThrowsConflictWithoutSaving() {
        when(postRepository.findByIdForShare(5L)).thenReturn(Optional.of(post));
        when(metricRepository.existsByPostIdAndCapturedAt(5L, FROM)).thenReturn(true);

        assertThatThrownBy(() -> metricService.create(new MetricCreateRequest(5L, FROM, 0L, 0L, 0L, 0L)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Bài viết id = 5 đã có snapshot tại 2026-03-01T00:00:00Z");
        verify(metricRepository, never()).save(any());
    }

    @Test
    void updateLocksSnapshotAndExcludesItselfFromDuplicateCheck() {
        SocialMetric metric = sampleMetric(11L);
        Instant newTime = Instant.parse("2026-03-01T02:00:00Z");
        when(metricRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(metric));
        when(metricRepository.existsByPostIdAndCapturedAtAndIdNot(5L, newTime, 11L)).thenReturn(false);
        when(metricRepository.saveAndFlush(metric)).thenReturn(metric);

        MetricResponse response = metricService.update(11L,
                new MetricUpdateRequest(Instant.parse("2026-03-01T02:00:00.000000500Z"), 200L, 20L, 10L, 5500L,
                        UPDATED.plusNanos(500)));

        assertThat(response).isEqualTo(new MetricResponse(11L, 5L, 200, 20, 10, 5500, newTime, CREATED, UPDATED));
    }

    @Test
    void updateUnknownIdThrowsNotFoundWithoutSaving() {
        when(metricRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricService.update(99L, new MetricUpdateRequest(FROM, 0L, 0L, 0L, 0L, UPDATED)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy snapshot có id = 99");
        verify(metricRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateToTimeOfAnotherSnapshotThrowsConflictWithoutSaving() {
        SocialMetric metric = sampleMetric(11L);
        when(metricRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(metric));
        when(metricRepository.existsByPostIdAndCapturedAtAndIdNot(5L, TO, 11L)).thenReturn(true);

        assertThatThrownBy(() -> metricService.update(11L, new MetricUpdateRequest(TO, 0L, 0L, 0L, 0L, UPDATED)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Bài viết id = 5 đã có snapshot tại 2026-03-08T00:00:00Z");
        verify(metricRepository, never()).saveAndFlush(any());
        assertThat(metric.getCapturedAt()).isEqualTo(Instant.parse("2026-03-01T01:00:00Z"));
    }

    @Test
    void updateWithStaleUpdatedAtThrowsConflictBeforeDuplicateCheck() {
        SocialMetric metric = sampleMetric(11L);
        when(metricRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(metric));

        assertThatThrownBy(() -> metricService.update(11L, new MetricUpdateRequest(TO, 0L, 0L, 0L, 0L, CREATED)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Snapshot id = 11 đã bị sửa sau khi bạn tải, hãy tải lại rồi thử lại");
        verify(metricRepository, never()).existsByPostIdAndCapturedAtAndIdNot(any(), any(), any());
        verify(metricRepository, never()).saveAndFlush(any());
        assertThat(metric.getLikes()).isEqualTo(120);
    }

    @Test
    void deleteChecksDeletedRowCount() {
        when(metricRepository.deleteByIdReturningCount(11L)).thenReturn(1);
        when(metricRepository.deleteByIdReturningCount(99L)).thenReturn(0);

        metricService.delete(11L);
        assertThatThrownBy(() -> metricService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy snapshot có id = 99");
    }

    private SocialMetric sampleMetric(long id) {
        SocialMetric metric = new SocialMetric(post, Instant.parse("2026-03-01T01:00:00Z"));
        metric.setLikes(120);
        metric.setShares(15);
        metric.setComments(8);
        metric.setFollowers(5400);
        return persisted(metric, id, CREATED, UPDATED);
    }
}
