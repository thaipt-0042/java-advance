package com.sunasterisk.socialanalytics.service;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.dto.PageQuery;
import com.sunasterisk.socialanalytics.dto.PageResponse;
import com.sunasterisk.socialanalytics.dto.PostCreateRequest;
import com.sunasterisk.socialanalytics.dto.PostFilter;
import com.sunasterisk.socialanalytics.dto.PostResponse;
import com.sunasterisk.socialanalytics.dto.PostUpdateRequest;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.exception.ConflictException;
import com.sunasterisk.socialanalytics.exception.ResourceNotFoundException;
import com.sunasterisk.socialanalytics.repository.PostRepository;
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
class PostServiceTests {

    private static final Instant CREATED = Instant.parse("2026-03-01T03:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-03-02T04:00:00Z");

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private PostService postService;

    @Test
    void searchEscapesKeywordAndUsesFixedSortAndPaging() {
        Post post = samplePost(7L);
        when(postRepository.search(eq(SocialPlatform.TWITTER), eq("%50!%!_x%"), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(post), inv.getArgument(2), 41));

        PageResponse<PostResponse> page = postService.search(
                new PostFilter(SocialPlatform.TWITTER, "  50%_x "), new PageQuery(2, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).search(eq(SocialPlatform.TWITTER), eq("%50!%!_x%"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(page.content()).extracting(PostResponse::id).containsExactly(7L);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void searchWithoutKeywordSkipsPatternAndUsesDefaultPaging() {
        when(postRepository.search(isNull(), isNull(), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(2), 0));

        postService.search(new PostFilter(null, "   "), new PageQuery(null, null));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).search(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void getMapsEveryField() {
        when(postRepository.findById(7L)).thenReturn(Optional.of(samplePost(7L)));

        PostResponse response = postService.get(7L);

        assertThat(response).isEqualTo(new PostResponse(7L, SocialPlatform.FACEBOOK, "ext-7", "sun.asterisk",
                "https://facebook.com/p/7", "Xin chào", Instant.parse("2026-02-28T10:00:00Z"), CREATED, UPDATED));
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        when(postRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.get(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy bài viết có id = 99");
    }

    @Test
    void createNormalizesInputBeforeSaving() {
        when(postRepository.existsByPlatformAndExternalId(SocialPlatform.FACEBOOK, "ext-1")).thenReturn(false);
        when(postRepository.save(any(Post.class)))
                .thenAnswer(inv -> persisted(inv.<Post>getArgument(0), 1L, CREATED, CREATED));

        PostResponse response = postService.create(new PostCreateRequest(SocialPlatform.FACEBOOK, " ext-1 ",
                " sun.asterisk ", "  ", "", Instant.parse("2026-02-28T10:00:00.123456789Z")));

        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(saved.capture());
        Post post = saved.getValue();
        assertThat(post.getPlatform()).isEqualTo(SocialPlatform.FACEBOOK);
        assertThat(post.getExternalId()).isEqualTo("ext-1");
        assertThat(post.getAccountName()).isEqualTo("sun.asterisk");
        assertThat(post.getUrl()).isNull();
        assertThat(post.getContent()).isNull();
        // DATETIME(6) keeps microseconds: truncated before saving so the response matches what is stored.
        assertThat(post.getPublishedAt()).isEqualTo(Instant.parse("2026-02-28T10:00:00.123456Z"));
        assertThat(response).isEqualTo(new PostResponse(1L, SocialPlatform.FACEBOOK, "ext-1", "sun.asterisk",
                null, null, Instant.parse("2026-02-28T10:00:00.123456Z"), CREATED, CREATED));
    }

    @Test
    void createDuplicateThrowsConflictWithoutSaving() {
        when(postRepository.existsByPlatformAndExternalId(SocialPlatform.TWITTER, "ext-1")).thenReturn(true);

        assertThatThrownBy(() -> postService.create(
                new PostCreateRequest(SocialPlatform.TWITTER, "ext-1", "a", null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Bài viết TWITTER có externalId = ext-1 đã tồn tại");
        verify(postRepository, never()).save(any());
    }

    @Test
    void updateReadsWithLockAndReplacesEditableFields() {
        Post post = samplePost(7L);
        when(postRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
        when(postRepository.saveAndFlush(post)).thenReturn(post);

        PostResponse response = postService.update(7L, new PostUpdateRequest(" new.account ",
                " https://x.com/p/7 ", " Nội dung mới ", Instant.parse("2026-02-27T00:00:00.000000999Z")));

        assertThat(response).isEqualTo(new PostResponse(7L, SocialPlatform.FACEBOOK, "ext-7", "new.account",
                "https://x.com/p/7", "Nội dung mới", Instant.parse("2026-02-27T00:00:00Z"), CREATED, UPDATED));
        verify(postRepository, never()).findById(any());
    }

    @Test
    void updateUnknownIdThrowsNotFoundWithoutSaving() {
        when(postRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.update(99L, new PostUpdateRequest("a", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(postRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteChecksDeletedRowCount() {
        when(postRepository.deleteByIdReturningCount(7L)).thenReturn(1);
        when(postRepository.deleteByIdReturningCount(99L)).thenReturn(0);

        postService.delete(7L);
        assertThatThrownBy(() -> postService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy bài viết có id = 99");
    }

    private static Post samplePost(long id) {
        Post post = new Post(SocialPlatform.FACEBOOK, "ext-" + id, "sun.asterisk");
        post.setUrl("https://facebook.com/p/" + id);
        post.setContent("Xin chào");
        post.setPublishedAt(Instant.parse("2026-02-28T10:00:00Z"));
        return persisted(post, id, CREATED, UPDATED);
    }
}
