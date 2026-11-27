package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPlatformAndExternalId(SocialPlatform platform, String externalId);

    boolean existsByPlatformAndExternalId(SocialPlatform platform, String externalId);

    /**
     * Reads the post and locks its row until the transaction ends ({@code SELECT ... FOR UPDATE}). Used before
     * modifying it: a concurrent delete either waits for us or makes this return empty (404), instead of our
     * UPDATE matching 0 rows at flush (500).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") Long id);

    /**
     * Reads the post with a shared lock ({@code SELECT ... FOR SHARE}) so it can't be deleted while a snapshot is
     * being added to it: a concurrent delete makes this wait and then return empty (404), instead of a foreign key
     * violation on insert.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForShare(@Param("id") Long id);

    /**
     * Existing posts among {@code externalIds} of one platform, locked like {@link #findByIdForUpdate} so a
     * concurrent delete can't make the later UPDATE match 0 rows. One query for a whole import instead of one per row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.platform = :platform and p.externalId in :externalIds")
    List<Post> findAllForUpdate(@Param("platform") SocialPlatform platform,
            @Param("externalIds") Collection<String> externalIds);

    /** Deletes without loading the post first; returns the number of deleted rows (0 if it doesn't exist). */
    @Modifying
    @Query("delete from Post p where p.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);

    /**
     * Posts filtered by platform and by a LIKE pattern over content / account name; {@code null} skips a filter.
     * The pattern must be built with {@code LikePatterns.contains} (escape character {@code !}).
     */
    @Query("""
            select p from Post p
            where (:platform is null or p.platform = :platform)
              and (:pattern is null
                   or p.content like :pattern escape '!'
                   or p.accountName like :pattern escape '!')
            """)
    Page<Post> search(@Param("platform") SocialPlatform platform, @Param("pattern") String pattern, Pageable pageable);
}
