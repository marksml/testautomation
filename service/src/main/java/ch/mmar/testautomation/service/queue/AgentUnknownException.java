package ch.mmar.testautomation.service.queue;

/** Thrown when an agent that was never registered tries to claim work → {@code 409 AGENT_UNKNOWN}. */
public class AgentUnknownException extends RuntimeException {

    private final String agentId;

    public AgentUnknownException(String agentId) {
        super("agent '" + agentId + "' is not registered");
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }
}
