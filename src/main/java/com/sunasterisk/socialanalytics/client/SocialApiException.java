package com.sunasterisk.socialanalytics.client;

/** A call to a social network failed; the message is safe to log (no token). */
public class SocialApiException extends RuntimeException {

    public SocialApiException(String message) {
        super(message);
    }
}
