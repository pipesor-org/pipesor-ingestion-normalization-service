package org.pipesor.ingestion.auth.application.ports;

/**
 * Runs login persistence steps in one database transaction: either all of them commit or none does.
 * The work must not perform remote I/O.
 */
@FunctionalInterface
public interface LoginTransactions {
    void inTransaction(Runnable work);
}
