package in.yesmadam.botin.agent;

import in.yesmadam.botin.escalation.EscalationContext;
import in.yesmadam.botin.escalation.EscalationContextRepository;
import in.yesmadam.botin.process.AgentCompletionDelegate;
import in.yesmadam.botin.process.ProcessVariables;
import in.yesmadam.botin.session.Ticket;
import in.yesmadam.botin.session.TicketRepository;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The agent side of the handover.
 *
 * THE QUEUE IS NOT A TABLE WE MAINTAIN. It is Flowable's User Task store, which means
 * the work item and the process waiting on it are the same object and cannot drift
 * apart. A separate "agent_task" table would have to be kept in step with the process
 * by hand, and the failure mode — a queue entry whose process has moved on, or a
 * process waiting on a queue entry nobody can see — is exactly the silent kind.
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    /** Matches the userTask id in concern-generic.bpmn20.xml. */
    public static final String AGENT_TASK_KEY = "agentConnect";

    private final TaskService tasks;
    private final TicketRepository tickets;
    private final EscalationContextRepository contexts;

    public AgentTaskService(TaskService tasks, TicketRepository tickets,
                            EscalationContextRepository contexts) {
        this.tasks = tasks;
        this.tickets = tickets;
        this.contexts = contexts;
    }

    /** Everything waiting, oldest first — the order a queue should be worked in. */
    @Transactional(readOnly = true)
    public List<AgentTaskView> open() {
        return tasks.createTaskQuery()
                .taskDefinitionKey(AGENT_TASK_KEY)
                .includeProcessVariables()
                .orderByTaskCreateTime().asc()
                .list().stream()
                .map(AgentTaskService::view)
                .toList();
    }

    /**
     * Claim, so two agents cannot work the same partner.
     *
     * The assignee is checked here rather than left to the engine's own already-claimed
     * exception, so the second agent gets a sentence naming who has it — "someone else
     * is on this" is the useful half, and a stack trace does not carry it.
     */
    @Transactional
    public AgentTaskView claim(String taskId, String agentId) {
        Task task = require(taskId);

        if (task.getAssignee() != null && !task.getAssignee().equals(agentId)) {
            throw new TaskAlreadyClaimedException(taskId, task.getAssignee());
        }

        tasks.claim(taskId, agentId);

        ticketOf(task).ifPresent(t -> t.assignTo(agentId));
        log.info("agent {} claimed task {}", agentId, taskId);

        return view(require(taskId));
    }

    /**
     * The agent is done. Completing the task wakes the process, which closes the ticket
     * and writes the ending onto the session.
     *
     * NOTHING IS CLOSED HERE. It would be easy to close the ticket in this method and
     * complete the task afterwards — and then a failure between the two leaves a closed
     * ticket with a live process still waiting on it. The process owns the ending;
     * this method only hands it the agent's words.
     */
    @Transactional
    public void complete(String taskId, String agentId, String resolutionNote) {
        Task task = require(taskId);

        if (task.getAssignee() == null) {
            throw new TaskNotClaimedException(taskId);
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put(AgentCompletionDelegate.RESOLUTION_NOTE, resolutionNote);
        variables.put(AgentCompletionDelegate.RESOLVED_BY, agentId);

        tasks.complete(taskId, variables);
        log.info("agent {} completed task {}", agentId, taskId);
    }

    /** What the agent opens first. Never recomputed — see EscalationContext. */
    @Transactional(readOnly = true)
    public Optional<EscalationContext> contextFor(UUID ticketId) {
        return contexts.findById(ticketId);
    }

    private Task require(String taskId) {
        Task task = tasks.createTaskQuery().taskId(taskId).includeProcessVariables().singleResult();
        if (task == null) throw new TaskNotFoundException(taskId);
        return task;
    }

    private Optional<Ticket> ticketOf(Task task) {
        Object ticketId = task.getProcessVariables().get(ProcessVariables.TICKET_ID);
        return ticketId == null ? Optional.empty()
                                : tickets.findById(UUID.fromString(ticketId.toString()));
    }

    private static AgentTaskView view(Task task) {
        Map<String, Object> vars = task.getProcessVariables();
        return new AgentTaskView(
                task.getId(),
                str(vars.get(ProcessVariables.TICKET_ID)),
                str(vars.get(ProcessVariables.HELP_SESSION_ID)),
                str(vars.get(ProcessVariables.SP_ID)),
                str(vars.get(ProcessVariables.L2_CONCERN)),
                str(vars.get(ProcessVariables.TRIGGER_REASON)),
                str(vars.get(ProcessVariables.ACTION)),
                task.getAssignee(),
                task.getCreateTime() == null ? null : task.getCreateTime().toInstant());
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) {
            super("No open agent task " + taskId + " — it may already have been completed");
        }
    }

    public static class TaskAlreadyClaimedException extends RuntimeException {
        public TaskAlreadyClaimedException(String taskId, String assignee) {
            super("Task " + taskId + " is already being worked by " + assignee);
        }
    }

    public static class TaskNotClaimedException extends RuntimeException {
        public TaskNotClaimedException(String taskId) {
            super("Task " + taskId + " must be claimed before it can be completed");
        }
    }
}
