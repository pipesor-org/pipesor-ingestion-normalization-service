package org.pipesor.ingestion.capture.application.ports;

/** Returning normally means broker acceptance only; uncertain failures remain eligible for stable retries. */
public interface BrokerPublisher {
    void publish(PublicationOutput output);
}
