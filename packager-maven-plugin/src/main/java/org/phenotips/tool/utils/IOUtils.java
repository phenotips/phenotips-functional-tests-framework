/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.tool.utils;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.FileUtils;

/**
 * Helper class for various IO operations.
 *
 * @version $Id$
 * @since 1.0M1
 */
public final class IOUtils
{
    private IOUtils()
    {
        // Forbid instantiation of utility class
    }

    /**
     * Create the requested directory if it doesn't already exist.
     *
     * @param directory the directory to create
     */
    public static void createDirectory(File directory)
    {
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    /**
     * Copy all the files in a directory into another directory.
     *
     * @param sourceDirectory the directory to copy
     * @param targetDirectory the destination
     * @throws MojoExecutionException if copying the files failed
     */
    public static void copyDirectory(File sourceDirectory, File targetDirectory) throws MojoExecutionException
    {
        createDirectory(targetDirectory);
        try {
            FileUtils.copyDirectoryStructureIfModified(sourceDirectory, targetDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Failed to copy directory [%] to [%]", sourceDirectory,
                targetDirectory), e);
        }
    }

    /**
     * Copy a file from one place to another.
     *
     * @param source the file to copy
     * @param targetDirectory the destination
     * @throws MojoExecutionException if copying the file failed
     */
    public static void copyFile(File source, File targetDirectory) throws MojoExecutionException
    {
        try {
            FileUtils.copyFileToDirectoryIfModified(source, targetDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Failed to copy file [%] to [%]", source, targetDirectory),
                e);
        }
    }

    /**
     * Extract the contents of a .zip file into a directory.
     *
     * @param source the zip file to extract
     * @param targetDirectory the destination
     * @throws MojoExecutionException if unzipping the file failed
     */
    public static void unzip(File source, File targetDirectory) throws MojoExecutionException
    {
        createDirectory(targetDirectory);
        try {
            ZipUnArchiver unArchiver = new ZipUnArchiver();
            unArchiver.enableLogging(new ConsoleLogger(Logger.LEVEL_ERROR, "Package"));
            unArchiver.setSourceFile(source);
            unArchiver.setDestDirectory(targetDirectory);
            unArchiver.setOverwrite(true);
            unArchiver.extract();
        } catch (Exception e) {
            throw new MojoExecutionException(String.format("Error unpacking file [%s] into [%s]", source,
                targetDirectory), e);
        }
    }
}
