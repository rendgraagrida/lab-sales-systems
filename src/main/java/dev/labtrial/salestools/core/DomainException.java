package dev.labtrial.salestools.core;

import java.util.List;

public final class DomainException extends RuntimeException {
    private final int status;
    private final List<String> fields;
    public DomainException(int status, String message) {
        super(message);
        this.status = status;
        this.fields = List.of();
    }
    public DomainException(int status, String message, String... fields) {
        super(message);
        this.status = status;
        this.fields = List.of(fields);
    }
    public int status() { return status; }
    public List<String> fields() { return fields; }
}
