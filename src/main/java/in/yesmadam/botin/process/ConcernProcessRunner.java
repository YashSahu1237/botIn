package in.yesmadam.botin.process;

import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Starts a concern's process. One method, and a rule stated loudly enough that the
 * next person cannot miss it.
 */
@Service
public class ConcernProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ConcernProcessRunner.class);

    private final RuntimeService runtimeService;

    public ConcernProcessRunner(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * Start the process named by concern_catalogue.process_key.
     *
     * DO NOT read process variables from the returned instance. A T0 completes
     * synchronously inside this call, and a completed instance has its ACT_RU_* rows
     * deleted — variables included. Whatever the client needs was written to the
     * help_session row by the process's final Service Task. Re-read it from there.
     *
     * @return the process instance id, for the audit trail. Not for reading state.
     */
    public String start(String processKey, UUID helpSessionId, String spId,
                        String l2Concern, String selectedReference) {

        return start(processKey, helpSessionId, spId, l2Concern, selectedReference, null);
    }

    /**
     * @param preFetchedFacts a classification already made for this turn, or null.
     *
     * Exists so a real classifier is called ONCE per turn. The entry point has to
     * classify in order to decide whether to ask again, and without this the process
     * would immediately classify the same sentence a second time — a doubled cost and a
     * doubled latency on the critical path, for no new information.
     *
     * Optional on purpose: the process re-fetches when this is absent, so nothing
     * depends on the caller having done the work.
     */
    public String start(String processKey, UUID helpSessionId, String spId,
                        String l2Concern, String selectedReference, String preFetchedFacts) {

        Map<String, Object> variables = new HashMap<>();
        variables.put(ProcessVariables.HELP_SESSION_ID, helpSessionId.toString());
        variables.put(ProcessVariables.SP_ID, spId);
        variables.put(ProcessVariables.L2_CONCERN, l2Concern);
        variables.put(ProcessVariables.SELECTED_REFERENCE, selectedReference);
        if (preFetchedFacts != null && !preFetchedFacts.isBlank()) {
            variables.put(ProcessVariables.FACTS_JSON, preFetchedFacts);
        }

        return startWith(processKey, helpSessionId, variables);
    }

    /**
     * Start with variables the caller has already worked out.
     *
     * Exists for trigger A, where there is nothing left to decide: the partner has just
     * told us the answer was wrong, so tier, action and trigger are known before the
     * process begins and the escalation flow only has to carry them.
     *
     * The business key is the session id in both cases, so every instance ever started
     * for one conversation can be found by one query — which is what makes an escalation
     * after the fact traceable back to the resolution it rejected.
     */
    public String startWith(String processKey, UUID helpSessionId, Map<String, Object> variables) {
        String instanceId = runtimeService
                .startProcessInstanceByKey(processKey, helpSessionId.toString(), variables)
                .getProcessInstanceId();

        log.info("started process {} for session {} -> instance {}",
                processKey, helpSessionId, instanceId);
        return instanceId;
    }
}
