package in.yesmadam.botin.classifier;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * PLAN STEP 84 — the real client. Two failure modes, one outcome.
 *
 * CONSTRUCTED BY ClassifierConfig — see that class for why this is not a @Component.
 *
 * A 3-SECOND TIMEOUT, BECAUSE A PARTNER IS WAITING. This call sits on the critical path
 * of someone holding a phone. A classifier that takes ten seconds has already failed,
 * whatever it eventually returns, so the timeout is a product decision rather than an
 * infrastructure default and it is stated here in those terms.
 *
 * A CIRCUIT BREAKER, BECAUSE THE SECOND FAILURE IS FREE. Without one, a dead classifier
 * costs a full timeout on EVERY request — the service stays up and gets slower for
 * everybody, including the T0 and T1 partners who never needed a model at all. Spike 4
 * proved that once the breaker is OPEN the body does not execute, so a dead dependency
 * stops costing anything within ten calls.
 *
 * BOTH FAILURE MODES SET tier = T3, and they do it by returning NO_MATCH rather than by
 * deciding anything here. The decision tables already send an unmatched classification
 * to a human; making the failure path identical to the ordinary no-match path means
 * there is no second code path that could behave differently under load — which is
 * exactly when it would be least noticed.
 */
public class HttpClassifierClient implements ClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClassifierClient.class);

    private final RestTemplate http;
    private final String baseUrl;

    HttpClassifierClient(RestTemplateBuilder builder, String baseUrl, long timeoutMs) {
        this.baseUrl = baseUrl;
        this.http = builder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
        log.info("classifier configured at {} with a {}ms timeout", baseUrl, timeoutMs);
    }

    @Override
    @CircuitBreaker(name = "classifier", fallbackMethod = "unavailable")
    public Classification classifyIntent(String text) {
        return post("/classify-intent", Map.of("text", text));
    }

    @Override
    @CircuitBreaker(name = "classifier", fallbackMethod = "unavailableForReason")
    public Classification classifyReason(String l2Concern, String text) {
        return post("/classify", Map.of("text", text, "concern", l2Concern));
    }

    @Override
    public String describe() { return "http " + baseUrl; }

    private Classification post(String path, Map<String, Object> body) {
        return http.postForObject(baseUrl + path, body, Classification.class);
    }

    /**
     * The fallback the breaker calls. It returns the SAME value an honest "I don't
     * recognise this" returns, on purpose — see the class note.
     *
     * Logged at WARN with the exception type but not the stack: a classifier that is
     * down produces one of these per request, and a stack trace per request buries
     * everything else in the log at exactly the moment someone is reading it.
     */
    @SuppressWarnings("unused")
    private Classification unavailable(String text, Throwable t) {
        log.warn("classify-intent unavailable ({}) — no confident match", t.getClass().getSimpleName());
        return Classification.NO_MATCH;
    }

    @SuppressWarnings("unused")
    private Classification unavailableForReason(String l2Concern, String text, Throwable t) {
        log.warn("classify unavailable for {} ({}) — no confident match",
                l2Concern, t.getClass().getSimpleName());
        return Classification.NO_MATCH;
    }
}
