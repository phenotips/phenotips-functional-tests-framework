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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;

/**
 * Maven helper class.
 *
 * @version $Id$
 * @since 1.0M1
 */
public final class MavenUtils
{
    private MavenUtils()
    {
        // Forbid instantiation of utility class
    }

    /**
     * Resolve a Maven artifact into a project object.
     *
     * @param artifact the coordinates of the project to resolve
     * @param session the current Maven session
     * @param projectBuilder the project builder active in this session
     * @return the project object, if successfully resolved
     * @throws MojoExecutionException if the project couldn't be resolved
     */
    public static MavenProject getMavenProject(Artifact artifact, MavenSession session, ProjectBuilder projectBuilder)
        throws MojoExecutionException
    {
        try {
            ProjectBuildingRequest request =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest())
                    // We don't want to execute any plugin here
                    .setProcessPlugins(false)
                    // It's not this plugin job to validate this pom.xml
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                    // Use the repositories configured for the built project instead of the global Maven ones
                    .setRemoteRepositories(session.getCurrentProject().getRemoteArtifactRepositories());
            // Note: build() will automatically get the POM artifact corresponding to the passed artifact.
            ProjectBuildingResult result = projectBuilder.build(artifact, request);
            return result.getProject();
        } catch (ProjectBuildingException e) {
            throw new MojoExecutionException(String.format("Failed to build project for [%s]", artifact), e);
        }
    }
}
