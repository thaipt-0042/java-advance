package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.Post;
import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPlatformAndExternalId(SocialPlatform platform, String externalId);

    boolean existsByPlatformAndExternalId(SocialPlatform platform, String externalId);

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
