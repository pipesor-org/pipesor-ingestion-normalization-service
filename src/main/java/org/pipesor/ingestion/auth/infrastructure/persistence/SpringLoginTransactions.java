package org.pipesor.ingestion.auth.infrastructure.persistence;

import org.pipesor.ingestion.auth.application.ports.LoginTransactions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One new transaction on the service's data source; JdbcTemplate statements inside it share its connection. */
public final class SpringLoginTransactions implements LoginTransactions {
    private final TransactionTemplate transaction;

    public SpringLoginTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public void inTransaction(Runnable work) { transaction.executeWithoutResult(status -> work.run()); }

    @Override public String toString() { return "SpringLoginTransactions[configured]"; }
}
