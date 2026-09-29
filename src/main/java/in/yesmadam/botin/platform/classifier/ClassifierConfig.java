package in.yesmadam.botin.platform.classifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WHICH CLASSIFIER IS WIRED IN — decided once, here, in an if statement anyone can read.
 *
 * THIS REPLACED A PAIR OF @ConditionalOnProperty ANNOTATIONS, and the reason is worth
 * keeping. The two were written to be mutually exclusive on "property missing" versus
 * "property present":
 *
 *     HttpClassifierClient  @ConditionalOnProperty(name = "botin.classifier.url")
 *     StubClassifierClient  @ConditionalOnProperty(..., havingValue = "", matchIfMissing = true)
 *
 * They were exclusive right up until `botin.classifier.url: ${CLASSIFIER_URL:}` was added
 * to application.yml. That makes the property PRESENT BUT EMPTY — a third state neither
 * condition was written for, and one that satisfies BOTH. Two beans, one injection
 * point, and the entire application context failed to start.
 *
 * Note the shape: adding a line of configuration, in a different file, changed which
 * beans exist. The conditions were correct in isolation and wrong together, and nothing
 * about either annotation hints that the other exists.
 *
 * An if statement cannot do that. There is one decision, it is in one place, it names
 * both outcomes, and "empty" and "missing" are visibly the same case.
 */
@Configuration
public class ClassifierConfig {

    private static final Logger log = LoggerFactory.getLogger(ClassifierConfig.class);

    @Bean
    public ClassifierClient classifierClient(
            RestTemplateBuilder builder,
            @Value("${botin.classifier.url:}") String url,
            @Value("${botin.classifier.timeout-ms:3000}") long timeoutMs) {

        // Unset, empty, or whitespace — all one case: there is no classifier.
        if (url == null || url.isBlank()) {
            log.warn("NO CLASSIFIER CONFIGURED — using the deterministic stub. Free text "
                   + "will match only fixture phrases; everything else routes to a human. "
                   + "Set CLASSIFIER_URL to use a real service.");
            return new StubClassifierClient();
        }

        return new HttpClassifierClient(builder, url.trim(), timeoutMs);
    }
}
