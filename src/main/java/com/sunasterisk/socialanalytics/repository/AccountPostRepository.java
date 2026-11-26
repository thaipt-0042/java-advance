package com.sunasterisk.socialanalytics.repository;

import com.sunasterisk.socialanalytics.entity.SocialPlatform;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Posts grouped by account for the metrics job (JDBC, no entity loaded). Accounts are compared like the
 * {@code account_name} column ({@code utf8mb4_0900_ai_ci}): names differing only by case or accents are one
 * account, as in the statistics.
 */
@Repository
public class AccountPostRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AccountPostRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every account that has posts, one name per account (by platform, then name). */
    public List<Account> findAccounts() {
        return jdbc.query("""
                        select platform, min(account_name) as account_name from posts
                        group by platform, account_name
                        order by platform, account_name
                        """,
                (rs, rowNum) -> new Account(SocialPlatform.valueOf(rs.getString("platform")),
                        rs.getString("account_name")));
    }

    public List<AccountPost> findPosts(Account account) {
        return jdbc.query("select id, external_id from posts where platform = :platform and account_name = :name",
                params(account), (rs, rowNum) -> new AccountPost(rs.getLong("id"), rs.getString("external_id")));
    }

    /**
     * Ids among {@code postIds} that still exist, read with a shared lock: they can't be deleted until the
     * transaction ends, so the snapshots inserted for them don't fail on the foreign key. Must run in a transaction.
     */
    public Set<Long> lockExisting(Collection<Long> postIds) {
        return jdbc.queryForList("select id from posts where id in (:ids) for share",
                        new MapSqlParameterSource("ids", postIds), Long.class)
                .stream()
                .collect(Collectors.toSet());
    }

    private static MapSqlParameterSource params(Account account) {
        return new MapSqlParameterSource()
                .addValue("platform", account.platform().name())
                .addValue("name", account.name());
    }

    public record Account(SocialPlatform platform, String name) {

        @Override
        public String toString() {
            return platform + "/" + name;
        }
    }

    public record AccountPost(long id, String externalId) {
    }
}
