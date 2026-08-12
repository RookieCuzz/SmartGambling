package me.arthed.smartgambling.data;

/** Raised when a copy-on-write machine-data transaction could not become durable. */
public class DataStoreException extends RuntimeException {
    public DataStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataStoreException(String message) {
        super(message);
    }
}
