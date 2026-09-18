package in.yesmadam.botin.api.dto;

/**
 * One selectable choice. {@code code} is what the client sends back; {@code label}
 * is what the partner reads. The client never interprets the code.
 */
public record Option(String code, String label) { }
