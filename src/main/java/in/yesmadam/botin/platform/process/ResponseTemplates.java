package in.yesmadam.botin.platform.process;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What the partner actually reads, keyed by the DMN action code.
 *
 * KEYED ON THE ACTION, NOT THE TIER. Two T1 outcomes can say opposite things — "we
 * already paid you" and "you did not travel, so there is nothing to pay" are both T1
 * and must never be confused. The action is the stable contract the decision tables
 * already emit, so it is the right key, and a table can change its mind about a tier
 * without changing a single word the partner sees.
 *
 * ONE FILE PER CONCERN, read from the classpath at startup:
 *
 *     resources/concern/&lt;l1&gt;/&lt;l2&gt;/templates.properties
 *     resources/platform/templates.properties
 *
 * This used to be a static Map in this class, which made it the one file every concern
 * had to edit. Three things the split buys: adding a concern stops touching a file other
 * concerns own, changing wording stops being a Java change, and per-language files become
 * possible later without a redesign (NFR-08 wants Hindi/Hinglish).
 *
 * NO FALLBACK PROSE. An action with no template gets a message that admits we have
 * nothing prepared, and the ERROR beside it names the code. A generic "your request has
 * been processed" would be the worst possible failure mode here: it reads as success
 * for an outcome nobody wrote a sentence for.
 *
 * TWO FILES CLAIMING ONE ACTION CODE IS A STARTUP FAILURE. Merging silently would pick a
 * winner by classpath order — so the sentence a partner reads would depend on which jar
 * happened to be scanned first, and it would differ between a developer's machine and
 * production. That is exactly the class of bug this codebase keeps finding: not a crash,
 * just a quietly wrong answer.
 *
 * Hinglish, in Devanagari-free Roman script, matching how partners are addressed in the
 * existing SOP.
 */
@Component
public class ResponseTemplates {

    private static final Logger log = LoggerFactory.getLogger(ResponseTemplates.class);

    private static final String[] LOCATIONS = {
            "classpath*:/concern/**/templates.properties",
            "classpath*:/platform/templates.properties",
    };

    private final Map<String, String> byAction = new TreeMap<>();

    @PostConstruct
    void load() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Map<String, String> sourceOf = new LinkedHashMap<>();

        for (String location : LOCATIONS) {
            for (Resource resource : resolver.getResources(location)) {
                String where = describe(resource);
                Properties properties = new Properties();
                // UTF-8 explicitly. Properties.load(InputStream) is ISO-8859-1, which would
                // mangle the first non-ASCII character anybody writes into these files.
                try (Reader reader = new InputStreamReader(
                        resource.getInputStream(), StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                for (String action : properties.stringPropertyNames()) {
                    String previous = sourceOf.put(action, where);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Two files both define the response template for '" + action
                              + "': " + previous + " and " + where + ". One action code, one owner "
                              + "— otherwise which sentence the partner reads depends on classpath order.");
                    }
                    byAction.put(action, properties.getProperty(action).trim());
                }
            }
        }

        if (byAction.isEmpty()) {
            throw new IllegalStateException(
                    "No response templates found on the classpath. Every bot resolution would "
                  + "reach the partner as 'nothing prepared'. Looked in: " + String.join(", ", LOCATIONS));
        }
        log.info("response templates loaded: {} action code(s) from {} file(s)",
                byAction.size(), sourceOf.values().stream().distinct().count());
    }

    /** Where a template came from, for the error above — the path, not a Resource's toString. */
    private static String describe(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int classes = url.lastIndexOf("/classes/");
            return classes < 0 ? url : url.substring(classes + "/classes/".length());
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    /** @return the prompt for this action, or null if nobody has written one. */
    public String promptFor(String action) {
        return byAction.get(action);
    }

    public boolean has(String action) {
        return byAction.containsKey(action);
    }

    /** Every action code that has words, for the console and for tests. */
    public Map<String, String> all() {
        return Map.copyOf(byAction);
    }
}
