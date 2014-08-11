/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.phenotips.tool.utils;

/**
 * Helper class for disabling verbose logs generated by XWiki during the XAR import.
 *
 * @version $Id$
 * @since 1.0M1
 */
public final class LogUtils
{
    private LogUtils()
    {
        // Forbid instantiation of utility class
    }

    /**
     * If running under Maven 3.1+, where slf4j-simple is used as the logging backend, configure the system so that logs
     * generated by XWiki or Hibernate follow the Maven logging settings.
     */
    public static void configureXWikiLogs()
    {
        String rootLogLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        if (rootLogLevel == null) {
            // slf4j-simple is not being used, probably running on Maven 3.0, the XML configuration will be used
            return;
        }
        String logLevel;
        switch (rootLogLevel) {
            case "debug":
            case "error":
                logLevel = rootLogLevel;
                break;
            default:
                logLevel = "warn";
        }
        System.setProperty("org.slf4j.simpleLogger.log.com.xpn", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.org.xwiki", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.org.hibernate", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.org.infinispan", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.org.reflections", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.org.hsqldb", logLevel);
        System.setProperty("org.slf4j.simpleLogger.log.hsqldb.db", logLevel);
    }
}
