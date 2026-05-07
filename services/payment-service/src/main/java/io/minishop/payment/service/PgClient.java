package io.minishop.payment.service;

import io.minishop.payment.config.PgProperties;
import io.minishop.payment.web.dto.PgChargeRequest;
import io.minishop.payment.web.dto.PgChargeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class PgClient {

    private static final Logger log = LoggerFactory.getLogger(PgClient.class);

    private final RestClient client;
    private final PgProperties props;

    public PgClient(RestClient pgRestClient, PgProperties props) {
        this.client = pgRestClient;
        this.props = props;
    }

    public PgChargeResponse charge(PgChargeRequest request) {
        try {
            return client.post()
                    .uri(props.url())
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new PgFailureException("PG returned " + res.getStatusCode());
                    })
                    .body(PgChargeResponse.class);
        } catch (ResourceAccessException e) {
            log.warn("PG call timed out or unreachable: {}", e.getMessage());
            throw new PgFailureException("PG timeout/unreachable: " + e.getMessage(), e);
        }
    }

    public static class PgFailureException extends RuntimeException {
        public PgFailureException(String message) { super(message); }
        public PgFailureException(String message, Throwable cause) { super(message, cause); }
    }
}
