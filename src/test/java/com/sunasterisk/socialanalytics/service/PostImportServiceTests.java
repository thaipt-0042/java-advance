package com.sunasterisk.socialanalytics.service;

import static com.sunasterisk.socialanalytics.support.TestEntities.persisted;
import static com.sunasterisk.socialanalytics.support.Xlsx.POST_HEADER;
import static com.sunasterisk.socialanalytics.support.Xlsx.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sunasterisk.socialanalytics.dto.ImportResultResponse;
import com.sunasterisk.socialanalytics.entity.ImportBatch;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelReader;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import com.sunasterisk.socialanalytics.repository.ImportBatchRepository;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.NewSnapshot;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.PostId;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.support.Xlsx;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Runs on a real {@link ExcelReader} (pure code) with mocked repositories and transaction manager. */
@ExtendWith(MockitoExtension.class)
class PostImportServiceTests {

    /** Has nanoseconds: timestamps written must be truncated to the column precision. */
    private static final Instant NOW = Instant.parse("2026-03-05T03:00:00.123456789Z");
    private static final Instant NOW_IN_DB = Instant.parse("2026-03-05T03:00:00.123456Z");
    private static final Instant STORED = Instant.parse("2026-03-01T03:00:00Z");

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostBulkRepository bulkRepository;

    @Mock
    private ImportBatchRepository batchRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ValidatorFactory validatorFactory;
    private PostImportService importService;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));
        importService = new PostImportService(new ExcelReader(validatorFactory.getValidator(), clock),
                postRepository, bulkRepository, batchRepository, clock, transactionManager);
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void insertsNewPostsAndOneSnapshotPerRowWithMetricsAtTheSameTime() {
        givenTransaction();
        givenBatchSaved();
        when(bulkRepository.findIds(SocialPlatform.FACEBOOK, List.of("a")))
                .thenReturn(List.of(new PostId(11, SocialPlatform.FACEBOOK, "a")));
        when(bulkRepository.findIds(SocialPlatform.TWITTER, List.of("b", "c"))).thenReturn(List.of(
                new PostId(12, SocialPlatform.TWITTER, "b"), new PostId(13, SocialPlatform.TWITTER, "c")));

        ImportResultResponse result = importFile(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page-a", "https://example.com/a", "hello", "2026-03-01T03:00:00.5Z",
                        10, 2, 3, 100),
                row("TWITTER", "b", "page-b"),
                row("TWITTER", "c", "page-c", null, null, null, 0, 0, 0, 0)));

        List<Post> posts = insertedPosts();
        assertThat(posts).extracting(Post::getPlatform, Post::getExternalId, Post::getAccountName).containsExactly(
                tuple(SocialPlatform.FACEBOOK, "a", "page-a"),
                tuple(SocialPlatform.TWITTER, "b", "page-b"),
                tuple(SocialPlatform.TWITTER, "c", "page-c"));
        assertThat(posts.getFirst()).extracting(Post::getUrl, Post::getContent, Post::getPublishedAt)
                .containsExactly("https://example.com/a", "hello", Instant.parse("2026-03-01T03:00:00.5Z"));
        assertThat(insertedSnapshots()).containsExactly(
                new NewSnapshot(11, 10, 2, 3, 100), new NewSnapshot(13, 0, 0, 0, 0));

        assertThat(result).isEqualTo(new ImportResultResponse(42L, 3, 3, 0, 2, NOW_IN_DB));
        assertThat(savedBatch()).extracting(ImportBatch::getFileName, ImportBatch::getTotalRows,
                ImportBatch::getCreatedPosts, ImportBatch::getUpdatedPosts, ImportBatch::getSnapshotsCreated,
                ImportBatch::getCapturedAt).containsExactly("posts.xlsx", 3, 3, 0, 2, NOW_IN_DB);
    }

    @Test
    void writesInOneReadCommittedTransaction() {
        givenTransaction();
        givenBatchSaved();

        importFile(Xlsx.of(POST_HEADER, row("FACEBOOK", "a", "page")));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(transactionManager).commit(any());
    }

    @Test
    void locksExistingPostsWithOneQueryPerPlatform() {
        givenTransaction();
        givenBatchSaved();

        importFile(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "p"), row("TWITTER", "b", "p"), row("FACEBOOK", "c", "p")));

        verify(postRepository).findAllForUpdate(SocialPlatform.FACEBOOK, Set.of("a", "c"));
        verify(postRepository).findAllForUpdate(SocialPlatform.TWITTER, Set.of("b"));
    }

    @Test
    void updatesExistingPostOnlyForColumnsInTheFileAndCountsOnlyRealChanges() {
        givenTransaction();
        givenBatchSaved();
        Post changed = existing(1, "a", "old-page", "https://example.com/old", "old content");
        Post same = existing(2, "b", "page-b", "https://example.com/b", "kept content");
        when(postRepository.findAllForUpdate(eq(SocialPlatform.FACEBOOK), anyCollection()))
                .thenReturn(List.of(changed, same));

        // No "content" column: stored content is kept. An empty url cell clears the url. "A" matches post "a"
        // like the external_id column does (case and accents ignored).
        ImportResultResponse result = importFile(Xlsx.of(
                row("platform", "externalId", "accountName", "url"),
                row("FACEBOOK", "A", "new-page", null),
                row("FACEBOOK", "b", "page-b", "https://example.com/b")));

        assertThat(changed).extracting(Post::getAccountName, Post::getUrl, Post::getContent)
                .containsExactly("new-page", null, "old content");
        assertThat(same).extracting(Post::getAccountName, Post::getUrl, Post::getContent)
                .containsExactly("page-b", "https://example.com/b", "kept content");
        verify(bulkRepository, never()).insertPosts(any(), any());
        verify(bulkRepository, never()).insertSnapshots(any(), any(), any());
        assertThat(result).extracting(ImportResultResponse::createdPosts, ImportResultResponse::updatedPosts,
                ImportResultResponse::snapshotsCreated, ImportResultResponse::capturedAt)
                .containsExactly(0, 1, 0, null);
    }

    @Test
    void updatesContentAndPublishedAtWhenTheirColumnsArePresent() {
        givenTransaction();
        givenBatchSaved();
        Post post = existing(1, "a", "page", null, "old content");
        when(postRepository.findAllForUpdate(eq(SocialPlatform.FACEBOOK), anyCollection())).thenReturn(List.of(post));

        ImportResultResponse result = importFile(Xlsx.of(
                row("platform", "externalId", "accountName", "content", "publishedAt"),
                row("FACEBOOK", "a", "page", "new content", "2026-03-01T03:00:00.000000999Z")));

        assertThat(post).extracting(Post::getContent, Post::getPublishedAt)
                .containsExactly("new content", Instant.parse("2026-03-01T03:00:00Z"));
        assertThat(result.updatedPosts()).isEqualTo(1);
    }

    @Test
    void addsSnapshotToExistingPostWithoutReadingItsIdAgain() {
        givenTransaction();
        givenBatchSaved();
        when(postRepository.findAllForUpdate(eq(SocialPlatform.FACEBOOK), anyCollection()))
                .thenReturn(List.of(existing(7, "a", "page", null, null)));

        importFile(Xlsx.of(POST_HEADER, row("FACEBOOK", "a", "page", null, null, null, 5, 6, 7, 8)));

        assertThat(insertedSnapshots()).containsExactly(new NewSnapshot(7, 5, 6, 7, 8));
        verify(bulkRepository, never()).findIds(any(), any());
    }

    @Test
    void rejectsSamePostTwiceInFileWithoutWriting() {
        assertThatThrownBy(() -> importFile(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page"),
                row("TWITTER", "a", "page"),
                row("FACEBOOK", "Á", "page"))))
                .isInstanceOfSatisfying(RowValidationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("File có 1 lỗi, chưa có dòng nào được lưu");
                    assertThat(ex.getErrors()).containsExactly(
                            new RowError(4, "externalId", "Bài viết này đã có ở dòng 2"));
                });

        verifyNoInteractions(transactionManager, postRepository, bulkRepository, batchRepository);
    }

    @Test
    void rejectsWholeFileWhenAnyRowIsInvalidWithAllErrorsSortedByRow() {
        assertThatThrownBy(() -> importFile(Xlsx.of(POST_HEADER,
                row("FACEBOOK", "a", "page"),
                row("FACEBOOK", "b", null, null, null, null, -1, 0, 0, 0),
                row("FACEBOOK", "a", "page"),
                row("TIKTOK", "c", "page"))))
                .isInstanceOfSatisfying(RowValidationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("File có 4 lỗi, chưa có dòng nào được lưu");
                    assertThat(ex.getErrors()).extracting(RowError::row, RowError::column).containsExactly(
                            tuple(3, "accountName"),
                            tuple(3, "likes"),
                            tuple(4, "externalId"),
                            tuple(5, "platform"));
                });

        verifyNoInteractions(transactionManager, postRepository, bulkRepository, batchRepository);
    }

    private ImportResultResponse importFile(byte[] file) {
        return importService.importPosts("posts.xlsx", new ByteArrayInputStream(file));
    }

    private void givenTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private void givenBatchSaved() {
        when(batchRepository.save(any())).thenAnswer(inv -> persisted(inv.getArgument(0), 42L, NOW, NOW));
    }

    private static Post existing(long id, String externalId, String accountName, String url, String content) {
        Post post = new Post(SocialPlatform.FACEBOOK, externalId, accountName);
        post.setUrl(url);
        post.setContent(content);
        return persisted(post, id, STORED, STORED);
    }

    @SuppressWarnings("unchecked")
    private List<Post> insertedPosts() {
        ArgumentCaptor<List<Post>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkRepository).insertPosts(captor.capture(), eq(NOW_IN_DB));
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<NewSnapshot> insertedSnapshots() {
        ArgumentCaptor<List<NewSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkRepository).insertSnapshots(captor.capture(), eq(NOW_IN_DB), eq(NOW_IN_DB));
        return captor.getValue();
    }

    private ImportBatch savedBatch() {
        ArgumentCaptor<ImportBatch> captor = ArgumentCaptor.forClass(ImportBatch.class);
        verify(batchRepository).save(captor.capture());
        return captor.getValue();
    }
}
