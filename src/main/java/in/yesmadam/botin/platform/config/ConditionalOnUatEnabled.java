package in.yesmadam.botin.platform.config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.*;

/** Tests and fixture-only runs switch UAT off entirely rather than mocking it. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "botin.uat.enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnUatEnabled {
}
