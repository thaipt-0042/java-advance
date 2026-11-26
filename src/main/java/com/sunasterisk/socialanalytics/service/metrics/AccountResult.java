package com.sunasterisk.socialanalytics.service.metrics;

import com.sunasterisk.socialanalytics.repository.AccountPostRepository.Account;
import org.jspecify.annotations.Nullable;

/** Outcome of one account in a run: snapshots written, or why it failed. */
public record AccountResult(Account account, int snapshotsCreated, @Nullable String error) {

    static AccountResult succeeded(Account account, int snapshotsCreated) {
        return new AccountResult(account, snapshotsCreated, null);
    }

    static AccountResult failed(Account account, String error) {
        return new AccountResult(account, 0, error);
    }

    public boolean isFailed() {
        return error != null;
    }
}
