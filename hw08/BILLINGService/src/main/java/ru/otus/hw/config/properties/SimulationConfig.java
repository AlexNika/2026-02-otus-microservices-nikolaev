package ru.otus.hw.config.properties;

/**
 * Simulation error bypass flag. Currently not used anywhere in BILLINGService (reserved for fault simulation);
 * kept for configuration compatibility.
 */
public interface SimulationConfig {

    boolean isSimulationErrorsBypassEnabled();
}
