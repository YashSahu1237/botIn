package in.yesmadam.botin.platform.agent;
import java.time.Instant;

/**
 * One row in the agent's queue.
 *
 * Enough to decide what to pick up, and nothing more. The facts, the history and what
 * the bot already did live on the escalation context, fetched when the agent actually
 * opens the case — a queue that carried all of that would be slow to list and would
 * tempt an agent to work from a summary instead of the record.
 *
 * @param triggerReason A..E for the five cross-cutting triggers, or CONCERN when the
 *                      concern's own decision table returned T3
 */
public record AgentTaskView(
        String taskId,
        String ticketId,
        String helpSessionId,
        String spId,
        String l2Concern,
        String triggerReason,
        String botAction,
        String assignee,
        Instant createdAt) { }
