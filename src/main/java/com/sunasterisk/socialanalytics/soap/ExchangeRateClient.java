package com.sunasterisk.socialanalytics.soap;

import com.sunasterisk.socialanalytics.soap.generated.GetExchangeRateRequest;
import com.sunasterisk.socialanalytics.soap.generated.GetExchangeRateResponse;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.springframework.core.env.Environment;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.ws.WebServiceException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.transport.http.JdkHttpClientMessageSender;

/**
 * Calls the exchange-rate SOAP service with {@link WebServiceTemplate}. URL and timeouts come from
 * {@link ExchangeRateProperties}; with no URL configured, the app calls its own mock ({@code /soap}) on the port it
 * actually listens on, read at call time (it is only known once the server has started; a mock-servlet test has
 * none, so the rate is unavailable there).
 *
 * <p>A SOAP fault, a timeout or an unreachable service becomes an {@link ExchangeRateUnavailableException}.
 */
@Component
public class ExchangeRateClient {

    private final WebServiceTemplate template;
    private final ExchangeRateProperties properties;
    private final Environment environment;

    public ExchangeRateClient(ExchangeRateProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan("com.sunasterisk.socialanalytics.soap.generated");
        // The sender builds its own HttpClient only in afterPropertiesSet(), which nothing calls here.
        JdkHttpClientMessageSender sender = new JdkHttpClientMessageSender(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build());
        sender.setRequestTimeout(properties.readTimeout());
        this.template = new WebServiceTemplate(marshaller);
        this.template.setMessageSender(sender);
    }

    /** For tests: {@code MockWebServiceServer.createServer(template)}. */
    WebServiceTemplate template() {
        return template;
    }

    /** Units of {@code to} for one {@code from}, on {@code date}. */
    public BigDecimal rate(String from, String to, LocalDate date) {
        GetExchangeRateRequest request = new GetExchangeRateRequest();
        request.setFrom(from);
        request.setTo(to);
        request.setDate(xmlDate(date));
        try {
            GetExchangeRateResponse response =
                    (GetExchangeRateResponse) template.marshalSendAndReceive(url(), request);
            return response.getRate();
        } catch (SoapFaultClientException ex) {
            throw new ExchangeRateUnavailableException("Exchange-rate service fault: " + ex.getFaultStringOrReason(),
                    ex);
        } catch (WebServiceException ex) {
            // Timeouts, connection refused, bad responses
            throw new ExchangeRateUnavailableException("Exchange-rate service unavailable: " + ex.getMessage(), ex);
        }
    }

    /** The configured URL, else the own mock on the port this server listens on (none in a mock-servlet test). */
    String url() {
        if (StringUtils.hasText(properties.url())) {
            return properties.url();
        }
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            throw new ExchangeRateUnavailableException(
                    "No exchange-rate URL configured and no local server to call");
        }
        return "http://localhost:" + port + "/soap";
    }

    private static XMLGregorianCalendar xmlDate(LocalDate date) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendarDate(date.getYear(), date.getMonthValue(),
                    date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
        } catch (DatatypeConfigurationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
