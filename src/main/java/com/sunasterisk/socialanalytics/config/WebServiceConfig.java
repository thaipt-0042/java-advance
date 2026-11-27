package com.sunasterisk.socialanalytics.config;

import com.sunasterisk.socialanalytics.soap.ExchangeRateEndpoint;
import com.sunasterisk.socialanalytics.soap.ExchangeRateProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * The SOAP endpoint of the mock exchange-rate service ({@link ExchangeRateEndpoint}), under
 * {@code spring.webservices.path} ({@code /soap}; not {@code /ws}, which is the WebSocket endpoint). The WSDL is
 * generated from the XSD and served at {@code /soap/exchange-rates.wsdl} (the bean name).
 *
 * <p>No {@code @EnableWs}: Spring Boot's web-services auto-configuration enables Spring-WS and registers the
 * {@code MessageDispatcherServlet}, but backs off entirely (servlet included) when the app declares its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExchangeRateProperties.class)
public class WebServiceConfig {

    @Bean
    XsdSchema exchangeRatesSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/exchange-rates.xsd"));
    }

    @Bean(name = "exchange-rates")
    DefaultWsdl11Definition exchangeRatesWsdl(XsdSchema exchangeRatesSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("ExchangeRatesPort");
        wsdl.setLocationUri("/soap");
        wsdl.setTargetNamespace(ExchangeRateEndpoint.NAMESPACE);
        wsdl.setSchema(exchangeRatesSchema);
        return wsdl;
    }
}
