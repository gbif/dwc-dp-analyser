package org.gbif.dp.cli;

import ch.qos.logback.classic.Level;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;

public class Logging {
    public static void setRootLevel(Level level) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(level);
    }
}
