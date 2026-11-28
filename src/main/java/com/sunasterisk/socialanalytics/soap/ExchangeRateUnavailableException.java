package com.sunasterisk.socialanalytics.soap;

/**
 * The exchange-rate service answered with a SOAP fault, timed out or could not be reached. The API answers 502;
 * the dashboard and the export show "không lấy được tỷ giá" instead.
 */
public class ExchangeRateUnavailableException extends RuntimeException {

    public ExchangeRateUnavailableException(String message) {
        super(message);
    }

    public ExchangeRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
