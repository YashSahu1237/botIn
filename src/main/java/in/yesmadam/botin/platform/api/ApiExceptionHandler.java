package in.yesmadam.botin.platform.api;

import in.yesmadam.botin.platform.agent.AgentTaskService.TaskAlreadyClaimedException;
import in.yesmadam.botin.platform.agent.AgentTaskService.TaskNotClaimedException;
import in.yesmadam.botin.platform.agent.AgentTaskService.TaskNotFoundException;
import in.yesmadam.botin.platform.csat.CsatService.CsatAlreadyRecordedException;
import in.yesmadam.botin.platform.csat.CsatService.CsatNotExpectedException;
import in.yesmadam.botin.platform.session.HelpSessionService.SessionClosedException;
import in.yesmadam.botin.platform.session.HelpSessionService.SessionNotFoundException;
import in.yesmadam.botin.platform.session.HelpSessionService.UnexpectedInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Turns the flow's own failures into answers rather than stack traces.
 *
 * These three are client errors, not server errors: a stale session id, a session
 * that has already ended, input arriving at a step that expects none. Returning 5xx
 * for any of them would put them in the wrong alerting bucket and hide the real ones.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "SESSION_NOT_FOUND", "detail", e.getMessage()));
    }

    @ExceptionHandler(SessionClosedException.class)
    public ResponseEntity<Map<String, String>> closed(SessionClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "SESSION_CLOSED", "detail", e.getMessage()));
    }

    @ExceptionHandler(UnexpectedInputException.class)
    public ResponseEntity<Map<String, String>> unexpected(UnexpectedInputException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "UNEXPECTED_INPUT", "detail", e.getMessage()));
    }

    /**
     * A task id that no longer resolves almost always means another agent finished it
     * first. That is a race between two people, not a fault, so it is a 404 with a
     * sentence that says what probably happened.
     */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> noTask(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "AGENT_TASK_NOT_FOUND", "detail", e.getMessage()));
    }

    /**
     * A second satisfaction answer. Refused rather than overwritten: the first one has
     * already decided whether a human was offered, and rewriting it would rewrite the
     * only number this measures.
     */
    @ExceptionHandler(CsatAlreadyRecordedException.class)
    public ResponseEntity<Map<String, String>> csatTwice(CsatAlreadyRecordedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CSAT_ALREADY_RECORDED", "detail", e.getMessage()));
    }

    /** Rating something that was never resolved — "we cannot help with that", say. */
    @ExceptionHandler(CsatNotExpectedException.class)
    public ResponseEntity<Map<String, String>> csatNotExpected(CsatNotExpectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CSAT_NOT_EXPECTED", "detail", e.getMessage()));
    }

    /** Two agents reached for the same partner. The second one is told whose it is. */
    @ExceptionHandler(TaskAlreadyClaimedException.class)
    public ResponseEntity<Map<String, String>> alreadyClaimed(TaskAlreadyClaimedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "AGENT_TASK_ALREADY_CLAIMED", "detail", e.getMessage()));
    }

    /** Completing an unclaimed task. The claim is what stops two agents on one partner. */
    @ExceptionHandler(TaskNotClaimedException.class)
    public ResponseEntity<Map<String, String>> notClaimed(TaskNotClaimedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "AGENT_TASK_NOT_CLAIMED", "detail", e.getMessage()));
    }

    /**
     * A URL THAT DOES NOT EXIST IS THE CALLER'S MISTAKE, NOT OURS.
     *
     * ===================================================================
     * FOUND BY A TEST THAT WAS LOOKING FOR SOMETHING ELSE
     * ===================================================================
     *
     * DemoIsolationTest asked for a 404 on `/demo/flags` outside the demo profile and got
     * a 500. The catch-all below was swallowing Spring's own no-handler exception, which
     * meant EVERY mistyped URL in this service reported a server fault:
     *
     *   - A client with a typo is told the backend is broken, and files a bug against us.
     *   - Every scan, probe and stale bookmark writes a full stack trace to the error log.
     *   - Real 500s — the ones that matter — sit in that noise, indistinguishable.
     *
     * The last one is the expensive part. An alert on 5xx rate is worthless when the
     * baseline is "somebody browsed a wrong path", and the first instinct on a genuine
     * incident is to assume it is more of the same.
     *
     * BOTH EXCEPTIONS, deliberately. Spring throws NoResourceFoundException when the
     * static-resource handler ends up with the request and NoHandlerFoundException when
     * nothing is mapped at all, and which one you meet depends on configuration somewhere
     * else. Handling one and guessing is how this returns to 500 after an upgrade.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> noSuchEndpoint(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND"));
    }

    /** POST to a GET-only path. The same family: the caller's mistake, reported as ours. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "METHOD_NOT_ALLOWED", "detail", e.getMessage()));
    }

    /**
     * Malformed or missing JSON. A 400, and the detail is kept, because this one the
     * caller CAN fix — and an app team debugging a client against a 500 will look in
     * entirely the wrong place first.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "MALFORMED_REQUEST"));
    }

    /**
     * Anything else is ours. Log it with the stack, tell the client nothing about it.
     * A partner-facing surface must never leak an internal message.
     *
     * KEEP THE HANDLERS ABOVE ABOVE THIS ONE. A catch-all on Exception catches everything
     * Spring throws too, including its way of saying "that is not a real address" — which
     * is exactly how this became a bug.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unhandled(Exception e) {
        log.error("Unhandled failure serving a help request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "INTERNAL"));
    }
}
