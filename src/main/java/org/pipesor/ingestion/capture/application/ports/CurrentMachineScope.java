package org.pipesor.ingestion.capture.application.ports;

import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

/** Must verify the current machine authorization, including its exact revision; never use human tokens. */
public interface CurrentMachineScope {
    enum Decision { AUTHORIZED, CHANGED_OR_LOST, UNAVAILABLE }
    Decision verify(CaptureScope expected);
}
