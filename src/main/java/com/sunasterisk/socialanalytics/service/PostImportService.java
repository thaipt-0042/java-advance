package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.dto.ImportResultResponse;
import com.sunasterisk.socialanalytics.dto.PostImportRow;
import com.sunasterisk.socialanalytics.entity.ImportBatch;
import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import com.sunasterisk.socialanalytics.excel.ExcelReader;
import com.sunasterisk.socialanalytics.excel.ExcelRow;
import com.sunasterisk.socialanalytics.excel.ExcelSheet;
import com.sunasterisk.socialanalytics.exception.RowError;
import com.sunasterisk.socialanalytics.exception.RowValidationException;
import com.sunasterisk.socialanalytics.repository.ImportBatchRepository;
import com.sunasterisk.socialanalytics.repository.PostRepository;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository;
import com.sunasterisk.socialanalytics.repository.PostBulkRepository.NewSnapshot;
import com.sunasterisk.socialanalytics.util.DbTime;
import java.io.InputStream;
import java.text.CollationKey;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports posts (and optionally one metric snapshot per post) from an Excel file. Rules (docs/cau-hoi-lam-ro.md Q3,
 * Q4):
 *
 * <ul>
 *   <li>All or nothing: any invalid row (or the same post twice in the file) rejects the whole file with every error
 *       by row number, and nothing is written.</li>
 *   <li>A post that already exists (same platform + externalId, compared like the column: ignoring case and accents)
 *       is updated from the file. Only columns present in the file are updated: a file without a {@code content}
 *       column keeps the stored content, while an empty cell in a present column clears it, like PUT.</li>
 *   <li>Every snapshot of one import has the same {@code capturedAt}: the import time.</li>
 *   <li>Once committed, {@code IMPORT_COMPLETED} is sent so the statistics are recomputed (#12).</li>
 * </ul>
 *
 * <p>The file is read and validated outside the transaction; the writes then run in one short transaction at
 * READ COMMITTED. New posts and snapshots are inserted in JDBC batches ({@link PostBulkRepository}). Existing
 * posts are read with a row lock (one query per platform), so a concurrent delete can't
 * turn the update into a 500. READ COMMITTED avoids the gap locks REPEATABLE READ would take for the external ids
 * that don't exist yet: two imports adding posts at the same time would otherwise deadlock. If both add the same
 * new post, the second one fails on the UNIQUE constraint (409) and none of its rows is saved.
 */
@Service
public class PostImportService {

    private static final Logger log = LoggerFactory.getLogger(PostImportService.class);

    public static final int MAX_ROWS = 5_000;

    private final ExcelReader excelReader;
    private final PostRepository postRepository;
    private final PostBulkRepository bulkRepository;
    private final ImportBatchRepository batchRepository;
    private final StatsChanges statsChanges;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PostImportService(ExcelReader excelReader, PostRepository postRepository,
            PostBulkRepository bulkRepository, ImportBatchRepository batchRepository, StatsChanges statsChanges,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.excelReader = excelReader;
        this.postRepository = postRepository;
        this.bulkRepository = bulkRepository;
        this.batchRepository = batchRepository;
        this.statsChanges = statsChanges;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public ImportResultResponse importPosts(String fileName, InputStream file) {
        ExcelSheet<PostImportRow> sheet = excelReader.read(file, PostImportRow.class, MAX_ROWS);
        List<RowError> errors = new ArrayList<>(sheet.errors());
        errors.addAll(duplicatesInFile(sheet.rows()));
        if (!errors.isEmpty()) {
            throw new RowValidationException(
                    "File có %d lỗi, chưa có dòng nào được lưu".formatted(errors.size()), errors);
        }
        ImportBatch batch = transaction.execute(status -> save(fileName, sheet));
        log.info("Imported {}: {} rows, {} new posts, {} updated posts, {} snapshots (batch {})", fileName,
                batch.getTotalRows(), batch.getCreatedPosts(), batch.getUpdatedPosts(), batch.getSnapshotsCreated(),
                batch.getId());
        return ImportResultResponse.from(batch);
    }

    private static List<RowError> duplicatesInFile(List<ExcelRow<PostImportRow>> rows) {
        PostKeys keys = new PostKeys();
        Map<PostKey, Integer> firstRow = new HashMap<>();
        List<RowError> errors = new ArrayList<>();
        for (ExcelRow<PostImportRow> row : rows) {
            Integer first = firstRow.putIfAbsent(keys.of(row.value()), row.rowNumber());
            if (first != null) {
                errors.add(new RowError(row.rowNumber(), "externalId",
                        "Bài viết này đã có ở dòng %d".formatted(first)));
            }
        }
        return errors;
    }

    private ImportBatch save(String fileName, ExcelSheet<PostImportRow> sheet) {
        PostKeys keys = new PostKeys();
        Map<PostKey, Post> existing = lockExisting(sheet.rows(), keys);
        // created_at / updated_at of the inserted rows, and captured_at of every snapshot of this import
        Instant now = DbTime.toColumnPrecision(clock.instant());
        List<Post> newPosts = new ArrayList<>();
        List<Long> accountChanged = new ArrayList<>();
        int updated = 0;
        for (ExcelRow<PostImportRow> excelRow : sheet.rows()) {
            PostImportRow row = excelRow.value();
            Post post = existing.get(keys.of(row));
            if (post == null) {
                newPosts.add(newPost(row));
                continue;
            }
            if (!post.getAccountName().equals(row.accountName())) {
                accountChanged.add(post.getId());
            }
            if (applyChanges(post, row, sheet.columns())) {
                // Written by JPA dirty checking at commit.
                updated++;
            }
        }
        if (!newPosts.isEmpty()) {
            bulkRepository.insertPosts(newPosts, now);
        }
        List<NewSnapshot> snapshots = snapshots(sheet.rows(), postIds(existing, newPosts, keys), keys);
        if (!snapshots.isEmpty()) {
            bulkRepository.insertSnapshots(snapshots, now, now);
        }
        ImportBatch batch = batchRepository.save(new ImportBatch(fileName, sheet.totalRows(), newPosts.size(),
                updated, snapshots.size(), snapshots.isEmpty() ? null : now));
        // Sent after commit (IMPORT_COMPLETED): today's snapshots, and the past of posts that changed account.
        statsChanges.importCompleted(batch.getId(), now, accountChanged);
        return batch;
    }

    /** Ids of every post of the file: the existing ones, and the new ones read back after the insert. */
    private Map<PostKey, Long> postIds(Map<PostKey, Post> existing, List<Post> newPosts, PostKeys keys) {
        Map<PostKey, Long> ids = new HashMap<>();
        existing.forEach((key, post) -> ids.put(key, post.getId()));
        newPosts.stream()
                .collect(Collectors.groupingBy(Post::getPlatform, () -> new EnumMap<>(SocialPlatform.class),
                        Collectors.mapping(Post::getExternalId, Collectors.toList())))
                .forEach((platform, externalIds) -> bulkRepository.findIds(platform, externalIds)
                        .forEach(post -> ids.put(keys.of(post.platform(), post.externalId()), post.id())));
        return ids;
    }

    private static List<NewSnapshot> snapshots(List<ExcelRow<PostImportRow>> rows, Map<PostKey, Long> ids,
            PostKeys keys) {
        return rows.stream()
                .map(ExcelRow::value)
                .filter(PostImportRow::hasMetrics)
                .map(row -> new NewSnapshot(ids.get(keys.of(row)), row.likes(), row.shares(), row.comments(),
                        row.followers()))
                .toList();
    }

    /** Existing posts of the file, locked; platforms in a fixed order so two imports lock in the same order. */
    private Map<PostKey, Post> lockExisting(List<ExcelRow<PostImportRow>> rows, PostKeys keys) {
        Map<SocialPlatform, Set<String>> idsByPlatform = rows.stream()
                .map(ExcelRow::value)
                .collect(Collectors.groupingBy(PostImportRow::platform, () -> new EnumMap<>(SocialPlatform.class),
                        Collectors.mapping(PostImportRow::externalId, Collectors.toSet())));
        return idsByPlatform.entrySet().stream()
                .flatMap(entry -> postRepository.findAllForUpdate(entry.getKey(), entry.getValue()).stream())
                // Two stored ids equal for the collator but not for MySQL can't both be the row's post; keep one.
                .collect(Collectors.toMap(keys::of, Function.identity(), (first, second) -> first));
    }

    private static Post newPost(PostImportRow row) {
        Post post = new Post(row.platform(), row.externalId(), row.accountName());
        post.setUrl(row.url());
        post.setContent(row.content());
        post.setPublishedAt(DbTime.toColumnPrecision(row.publishedAt()));
        return post;
    }

    /**
     * Copies the columns present in the file; returns whether anything changed. An unchanged post is not written,
     * so its {@code updatedAt} (which API clients send back on PUT) stays the same.
     */
    private static boolean applyChanges(Post post, PostImportRow row, Set<String> columns) {
        boolean changed = false;
        if (!post.getAccountName().equals(row.accountName())) {
            post.setAccountName(row.accountName());
            changed = true;
        }
        if (columns.contains("url") && !Objects.equals(post.getUrl(), row.url())) {
            post.setUrl(row.url());
            changed = true;
        }
        if (columns.contains("content") && !Objects.equals(post.getContent(), row.content())) {
            post.setContent(row.content());
            changed = true;
        }
        Instant publishedAt = DbTime.toColumnPrecision(row.publishedAt());
        if (columns.contains("publishedAt") && !Objects.equals(post.getPublishedAt(), publishedAt)) {
            post.setPublishedAt(publishedAt);
            changed = true;
        }
        return changed;
    }

    private record PostKey(SocialPlatform platform, CollationKey externalId) {
    }

    /**
     * Builds keys that compare {@code externalId} like the {@code external_id} column (utf8mb4_0900_ai_ci: case and
     * accents ignored), so a row matches the post the UNIQUE constraint would consider the same. Not thread-safe:
     * one instance per import.
     */
    private static final class PostKeys {

        private final Collator collator = Collator.getInstance(Locale.ROOT);

        PostKeys() {
            collator.setStrength(Collator.PRIMARY);
        }

        PostKey of(PostImportRow row) {
            return new PostKey(row.platform(), collator.getCollationKey(row.externalId()));
        }

        PostKey of(Post post) {
            return of(post.getPlatform(), post.getExternalId());
        }

        PostKey of(SocialPlatform platform, String externalId) {
            return new PostKey(platform, collator.getCollationKey(externalId));
        }
    }
}
