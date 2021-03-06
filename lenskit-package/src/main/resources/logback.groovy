/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2013 Regents of the University of Minnesota and contributors
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
import ch.qos.logback.classic.filter.ThresholdFilter

if (System.getProperty("log.debugConfig")?.toLowerCase() == "true") {
    statusListener(OnConsoleStatusListener)
}

def logFile = System.getProperty("log.file")
def appenders = ["CONSOLE"]
def rootLevel = INFO
def useColor = false
switch (System.getProperty("log.color", "auto").toLowerCase()) {
    case "yes":
    case "true":
        useColor=true
        break
    case "no":
    case "false":
        useColor=false
        break
    case "auto":
        useColor = System.console() != null
}

appender("CONSOLE", ConsoleAppender) {
    def filt = new ThresholdFilter()
    filter(ThresholdFilter) {
        level = "INFO"
    }
    encoder(PatternLayoutEncoder) {
        if (useColor) {
            pattern = "%highlight(%-5level) %white(%date{HH:mm:ss.SSS}) [%yellow(%thread)] %cyan(%logger{24}) %msg%n"
        } else {
            pattern = "%-5level %date{HH:mm:ss.SSS} [%thread] %logger{24} %msg%n"
        }
    }
}

if (logFile != null) {
    appender("LOGFILE", FileAppender) {
        file = logFile
        encoder(PatternLayoutEncoder) {
            pattern = "%date{HH:mm:ss.SSS} %level [%thread] %logger: %msg%n"
        }
    }
    appenders << "LOGFILE"
    rootLevel = DEBUG
}

logger("org.grouplens.grapht", WARN)
root(rootLevel, appenders)
