package com.sunasterisk.socialanalytics.soap;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

/** The mock service only knows USD and VND: answered with a SOAP client fault. */
@SoapFault(faultCode = FaultCode.CLIENT)
public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String from, String to) {
        super("Unsupported currency pair " + from + "/" + to + " (supported: USD, VND)");
    }
}
