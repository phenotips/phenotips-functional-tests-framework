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
package org.phenotips.tool.packager;

import org.phenotips.tool.utils.MavenUtils;
import org.phenotips.tool.utils.XContextFactory;
import org.phenotips.tool.xarimporter.Importer;

import org.xwiki.velocity.internal.log.SLF4JLogChute;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.repository.RepositorySystem;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.runtime.RuntimeConstants;
import org.codehaus.plexus.util.StringUtils;
import org.hibernate.cfg.Environment;

import com.xpn.xwiki.XWikiContext;

/**
 * Create a runnable PhenoTips instance using Jetty as the Servlet Container and HSQLDB as the Database.
 *
 * @version $Id$
 * @since 1.0M1
 */
@Mojo(
    name = "package",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresProject = true,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true)
public class PackageMojo extends AbstractMojo
{
    private static final String PHENOTIPS_GROUPID = "org.phenotips";

    private static final String XWIKI_PLATFORM_GROUPID = "org.xwiki.platform";

    private static final String TYPE_JAR = "jar";

    private static final String TYPE_XAR = "xar";

    private static final String TYPE_ZIP = "zip";

    private static final String TYPE_WAR = "war";

    private static final String CONTEXT_PATH = "phenotips";

    /** The directory where to create the packaging. */
    @Parameter(defaultValue = "${project.build.directory}/package")
    private File outputPackageDirectory;

    /** The directory where classes are put. */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File outputClassesDirectory;

    /** The directory where the HSQLDB database is generated. */
    @Parameter(defaultValue = "${project.build.directory}/database")
    private File databaseDirectory;

    /** The project being built. */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /** Builds a Model from a pom.xml. */
    @Component
    private ProjectBuilder projectBuilder;

    /** Used to look up Artifacts in the remote repository. */
    @Component
    private RepositorySystem repositorySystem;

    /** The current Maven session being executed. */
    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    /** Local repository to be used by the plugin to resolve dependencies. */
    @Parameter(defaultValue = "${localRepository}")
    private ArtifactRepository localRepository;

    /** List of remote repositories to be used by the plugin to resolve dependencies. */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}")
    private List<ArtifactRepository> remoteRepositories;

    /**
     * The user under which the import should be done. If not user is specified then we import with backup pack. For
     * example {@code superadmin}.
     */
    @Parameter
    private String importUser;

    /** The default skin to configure for the platform. This may be overridden in the instance preferences. */
    @Parameter(property = "xwiki.defaultSkin", defaultValue = "colibri")
    private String defaultSkin;

    /** The directory name where the data should be placed. */
    @Parameter(property = "xwiki.dataDirectory", defaultValue = "${project.build.directory}/package/data/")
    private File dataDirectory;

    /** The version to be used for the needed XWiki modules. */
    @Parameter(property = "xwiki.version")
    private String xwikiVersion;

    /** List of skin artifacts to include in the packaging. */
    @Parameter
    private List<SkinArtifactItem> skinArtifactItems;

    /**
     * Maps each dependency of type WAR to a context path which will be used as the target directory when the WAR
     * artifact is extracted. WARs that share the same context path are merged. The order of the WAR artifacts in the
     * dependency list is important because the last one can overwrite files from the previous ones if they share the
     * same context path.
     */
    @Parameter
    private Map<String, String> contextPathMapping;

    /**
     * Indicate if the package mojo is used for tests. Among other things it means it's then possible to skip it using
     * skipTetsts system property.
     */
    @Parameter(defaultValue = "true")
    private boolean test;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (isSkipExecution()) {
            getLog().info("Skipping execution");
            return;
        }

        File webappsDirectory = new File(this.outputPackageDirectory, "webapps");
        File webappDirectory = new File(webappsDirectory, CONTEXT_PATH);
        File webInfDirectory = new File(webappDirectory, "WEB-INF");
        File libDirectory = new File(webInfDirectory, "lib");

        getLog().info("Using platform version: " + this.xwikiVersion);

        // Step 1: Expand Jetty resources into the package output directory.
        expandJettyDistribution();

        // Step 2: Get the WAR dependencies and expand them in the package output directory.
        expandWebapps(webappsDirectory);

        // Step 3: Copy all JARs dependencies to the expanded WAR directory in WEB-INF/lib
        copyLibs(webInfDirectory);

        // Step 4: Copy compiled classes in the WEB-INF/classes directory. This allows the tests to provide custom
        // code, for example to override existing components for the test purpose.
        copyClasses(webInfDirectory);

        // Step 5: Generate and copy config files.
        generateConfigurationFiles(webInfDirectory);

        // Step 6: Copy HSQLDB JDBC Driver
        getLog().info("Copying HSQLDB JDBC Driver JAR ...");
        Artifact hsqldbArtifact = resolveHSQLDBArtifact();
        org.phenotips.tool.utils.IOUtils.copyFile(hsqldbArtifact.getFile(), libDirectory);

        // Step 7: Unzip the specified Skins. If no skin is specified then unzip the Colibri skin only.
        expandSkins(webappDirectory);

        // Step 8: Import specified XAR files into the database
        importXARs(webInfDirectory);
    }

    protected boolean isSkipExecution()
    {
        return isSkipTests();
    }

    private boolean isSkipTests()
    {
        if (this.test) {
            String property = System.getProperty("skipTests");
            return property != null && Boolean.valueOf(property);
        } else {
            return false;
        }
    }

    private void expandJettyDistribution() throws MojoExecutionException
    {
        getLog().info("Expanding Jetty Resources ...");
        Artifact jettyArtifact = resolveJettyArtifact();
        org.phenotips.tool.utils.IOUtils.unzip(jettyArtifact.getFile(), this.outputPackageDirectory);

        // Replace maven properties in start shell scripts
        VelocityContext context = createVelocityContext();
        Collection<File> startFiles =
            org.apache.commons.io.FileUtils.listFiles(this.outputPackageDirectory, new WildcardFileFilter(
                "start*"), null);

        // Note: Init is done once even if this method is called several times...
        Velocity.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM, new SLF4JLogChute());
        Velocity.init();
        for (File startFile : startFiles) {
            getLog().info(String.format("  Replacing variables in [%s]...", startFile));
            try {
                String content = org.apache.commons.io.FileUtils.readFileToString(startFile);
                OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(startFile));
                Velocity.evaluate(context, writer, "", content);
                writer.close();
            } catch (Exception e) {
                // Failed to read or write file...
                throw new MojoExecutionException(String.format("Failed to process start shell script [%s]", startFile),
                    e);
            }
        }
    }

    private void expandWebapps(File webappsDirectory) throws MojoExecutionException
    {
        getLog().info("Expanding WAR dependencies ...");
        for (Artifact warArtifact : resolveWarArtifacts()) {
            getLog().info("  ... Unzipping WAR: " + warArtifact.getFile());
            org.phenotips.tool.utils.IOUtils.unzip(warArtifact.getFile(), new File(webappsDirectory, CONTEXT_PATH));
        }
    }

    private void copyLibs(File libDirectory) throws MojoExecutionException
    {
        getLog().info("Copying JAR dependencies ...");
        org.phenotips.tool.utils.IOUtils.createDirectory(libDirectory);
        for (Artifact artifact : resolveJarArtifacts()) {
            getLog().info("  ... Copying JAR: " + artifact.getFile());
            org.phenotips.tool.utils.IOUtils.copyFile(artifact.getFile(), libDirectory);
        }
    }

    private void copyClasses(File webInfDirectory) throws MojoExecutionException
    {
        getLog().info("Copying Java Classes ...");
        File classesDirectory = new File(webInfDirectory, "classes");
        if (this.outputClassesDirectory.exists()) {
            org.phenotips.tool.utils.IOUtils.copyDirectory(this.outputClassesDirectory, classesDirectory);
        }
    }

    private void expandSkins(File webappDirectory) throws MojoExecutionException
    {
        getLog().info("Copying Skins ...");
        File skinsDirectory = new File(webappDirectory, "skins");
        if (this.skinArtifactItems != null) {
            for (SkinArtifactItem skinArtifactItem : this.skinArtifactItems) {
                Artifact skinArtifact = resolveArtifactItem(skinArtifactItem);
                org.phenotips.tool.utils.IOUtils.unzip(skinArtifact.getFile(), skinsDirectory);
            }
        } else {
            Artifact colibriArtifact =
                resolveArtifact(XWIKI_PLATFORM_GROUPID, "xwiki-platform-colibri", this.xwikiVersion, TYPE_ZIP);
            org.phenotips.tool.utils.IOUtils.unzip(colibriArtifact.getFile(), skinsDirectory);
        }
    }

    private Artifact resolveArtifactItem(ArtifactItem artifactItem) throws MojoExecutionException
    {
        // Resolve the version and the type:
        // - if specified in the artifactItem, use them
        // - if not specified look for them in the project dependencies
        String version = artifactItem.getVersion();
        String type = artifactItem.getType();
        if (version == null || type == null) {
            Map<String, Artifact> artifacts = this.project.getArtifactMap();
            String key = ArtifactUtils.versionlessKey(artifactItem.getGroupId(), artifactItem.getArtifactId());
            if (artifacts.containsKey(key)) {
                if (version == null) {
                    version = artifacts.get(key).getVersion();
                }
                if (type == null) {
                    type = artifacts.get(key).getType();
                }
            } else {
                // Default to the platform version
                if (version == null) {
                    version = this.xwikiVersion;
                }
                // Default to JAR
                if (type == null) {
                    type = TYPE_JAR;
                }
            }
        }

        // Resolve the artifact
        Artifact artifact =
            this.repositorySystem.createArtifact(artifactItem.getGroupId(), artifactItem.getArtifactId(), version, "",
                type);
        resolveArtifact(artifact);
        return artifact;
    }

    private void generateConfigurationFiles(File configurationFileTargetDirectory) throws MojoExecutionException
    {
        String parsedExtension = ".vm";
        getLog().info("Copying Configuration files ...");
        VelocityContext context = createVelocityContext();
        Artifact configurationResourcesArtifact =
            this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID, "xwiki-platform-tool-configuration-resources",
                this.xwikiVersion, "", TYPE_JAR);
        resolveArtifact(configurationResourcesArtifact);

        configurationFileTargetDirectory.mkdirs();

        try (JarInputStream jarInputStream =
                new JarInputStream(new FileInputStream(configurationResourcesArtifact.getFile()))) {
            JarEntry entry;
            while ((entry = jarInputStream.getNextJarEntry()) != null) {
                if (entry.getName().endsWith(parsedExtension)) {
                    String fileName = entry.getName().replace(parsedExtension, "");
                    File outputFile = new File(configurationFileTargetDirectory, fileName);
                    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outputFile));
                    getLog().info("Writing config file: " + outputFile);
                    // Note: Init is done once even if this method is called several times...
                    Velocity.init();
                    Velocity.evaluate(context, writer, "", IOUtils.toString(jarInputStream));
                    writer.close();
                    jarInputStream.closeEntry();
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to extract configuration files", e);
        }
    }

    private void importXARs(File webInfDirectory) throws MojoExecutionException
    {
        getLog().info(
            String.format("Import XAR dependencies %s...", this.importUser == null ? "as a backup pack"
                : "using user [" + this.importUser + "]"));
        Set<Artifact> xarArtifacts = resolveXARs();
        if (!xarArtifacts.isEmpty()) {
            Importer importer = new Importer();
            // Make sure that we generate the Database in the right directory
            // TODO: In the future control completely the Hibernate config from inside the packager plugin and not in
            // the project using the packager plugin
            System.setProperty(Environment.URL, "jdbc:hsqldb:file:" + this.databaseDirectory
                + "/xwiki_db;shutdown=true");

            XWikiContext xcontext;
            try {
                xcontext = XContextFactory.createXWikiContext("xwiki", new File(webInfDirectory, "hibernate.cfg.xml"));
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to create context to import XAR files", e);
            }

            for (Artifact xarArtifact : xarArtifacts) {
                getLog().info("  ... Importing XAR file: " + xarArtifact.getFile());

                try {
                    int nb = importer.importXAR(xarArtifact.getFile(), this.importUser, xcontext);

                    getLog().info("  .... Imported " + nb + " documents");
                } catch (Exception e) {
                    throw new MojoExecutionException(
                        String.format("Failed to import XAR [%s]", xarArtifact.toString()), e);
                }
            }

            // Copy database files to XWiki's data directory.
            File dataDir = this.dataDirectory;
            org.phenotips.tool.utils.IOUtils.copyDirectory(this.databaseDirectory, new File(dataDir, "database"));

            try {
                XContextFactory.disposeXWikiContext(xcontext);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to dispose XWiki context", e);
            }
        }
    }

    private Set<Artifact> resolveXARs() throws MojoExecutionException
    {
        Set<Artifact> xarArtifacts = new HashSet<Artifact>();

        Set<Artifact> artifacts = this.project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if (artifact.getType().equals(TYPE_XAR)) {
                    xarArtifacts.add(artifact);
                    resolveArtifact(artifact);
                }
            }
        }

        return xarArtifacts;
    }

    private Artifact resolveHSQLDBArtifact() throws MojoExecutionException
    {
        Artifact hsqldbArtifact = null;
        String groupId = "org.hsqldb";
        String artifactId = "hsqldb";

        Set<Artifact> artifacts = this.project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if (artifact.getType().equals(TYPE_JAR) && artifact.getGroupId().equals(groupId)
                    && artifact.getArtifactId().equals(artifactId)) {
                    hsqldbArtifact = artifact;
                    break;
                }
            }
        }

        // If the HSQLDB artifact wasn't defined, try to resolve the default HSQLDB JAR artifact
        if (hsqldbArtifact == null) {
            hsqldbArtifact = this.repositorySystem.createArtifact(groupId, artifactId, "2.3.2", "", TYPE_JAR);
        }

        if (hsqldbArtifact != null) {
            resolveArtifact(hsqldbArtifact);
        } else {
            throw new MojoExecutionException("Failed to locate the HSQLDB artifact in either the project "
                + "dependency list or using the specific [hsqldb:hsqldb] artifact name");
        }

        return hsqldbArtifact;
    }

    private Artifact resolveJettyArtifact() throws MojoExecutionException
    {
        Artifact jettyArtifact = null;
        String artifactId = "jetty-resources";

        Set<Artifact> artifacts = this.project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if (artifact.getType().equals(TYPE_ZIP) && artifact.getArtifactId().equals(artifactId)) {
                    jettyArtifact = artifact;
                    break;
                }
            }
        }

        // If the Jetty artifact wasn't defined, try to resolve the default Jetty artifact
        if (jettyArtifact == null) {
            jettyArtifact = this.repositorySystem.createArtifact(PHENOTIPS_GROUPID, artifactId,
                this.project.getVersion(), "", TYPE_ZIP);
        }

        if (jettyArtifact != null) {
            resolveArtifact(jettyArtifact);
        } else {
            throw new MojoExecutionException("Failed to locate the Jetty artifact in either the project "
                + "dependency list or using the specific [xwiki-platform-tool-jetty-resources] artifact name");
        }

        return jettyArtifact;
    }

    private Collection<Artifact> resolveWarArtifacts() throws MojoExecutionException
    {
        List<Artifact> warArtifacts = new ArrayList<Artifact>();

        // First look for dependencies of type WAR.
        for (Artifact artifact : this.project.getArtifacts()) {
            if (artifact.getType().equals(TYPE_WAR)) {
                warArtifacts.add(artifact);
            }
        }

        // If there are no WAR artifacts specified in the list of dependencies then use the default WAR artifacts.
        if (warArtifacts.isEmpty()) {
            warArtifacts.add(this.repositorySystem.createArtifact(PHENOTIPS_GROUPID, "phenotips-base-war",
                this.project.getVersion(), "", TYPE_WAR));
        }

        for (Artifact warArtifact : warArtifacts) {
            resolveArtifact(warArtifact);
        }

        return warArtifacts;
    }

    private Collection<Artifact> resolveJarArtifacts() throws MojoExecutionException
    {
        Set<Artifact> artifactsToResolve = this.project.getArtifacts();

        // Add mandatory dependencies if they're not explicitly specified.
        artifactsToResolve.addAll(getMandatoryJarArtifacts());

        // Resolve all artifacts transitively in one go.
        Set<Artifact> resolvedArtifacts = resolveTransitively(artifactsToResolve);

        // Remove the non JAR artifacts. Note that we need to include non JAR artifacts before the transitive resolve
        // because for example some XARs mayb depend on JARs and we need those JARs to be packaged!
        Set<Artifact> jarArtifacts = new HashSet<Artifact>();
        for (Artifact artifact : resolvedArtifacts) {
            // Note: test-jar is used in functional tests from time to time and we need to package them too.
            if (artifact.getType().equals(TYPE_JAR) || artifact.getType().equals("test-jar")) {
                jarArtifacts.add(artifact);
            }
        }

        return jarArtifacts;
    }

    private Set<Artifact> getMandatoryJarArtifacts() throws MojoExecutionException
    {
        Set<Artifact> mandatoryTopLevelArtifacts = new HashSet<Artifact>();

        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-oldcore", this.xwikiVersion, null, TYPE_JAR));

        // Required Plugins
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-skin-skinx", this.xwikiVersion, null, TYPE_JAR));

        // We shouldn't need those but right now it's mandatory since they are defined in the default web.xml file we
        // provide. We'll be able to remove them when we start using Servlet 3.0 -->
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-rest-server", this.xwikiVersion, null, TYPE_JAR));

        // Needed by platform-web but since we don't have any dep in platform-web's pom.xml at the moment (duplication
        // issue with XE/XEM and platform-web) we need to include it here FTM... Solution: get a better maven WAR plugin
        // with proper merge feature and then remove this...
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-uiextension-api", this.xwikiVersion, null, TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-localization-script", this.xwikiVersion, null, TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-localization-source-legacy", this.xwikiVersion, null, TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-security-bridge", this.xwikiVersion, null, TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-url-standard", this.xwikiVersion, null, TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-wiki-default", this.xwikiVersion, null, TYPE_JAR));

        // Ensures all logging goes through SLF4J and Logback.
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact("org.xwiki.commons",
            "xwiki-commons-logging-logback", this.xwikiVersion, "compile", TYPE_JAR));
        // Get the logging artifact versions from the top level XWiki Commons POM

        String slf4jGroupId = "org.slf4j";
        mandatoryTopLevelArtifacts.add(this.resolveManagedArtifact(slf4jGroupId, "jcl-over-slf4j", TYPE_JAR));
        mandatoryTopLevelArtifacts.add(this.resolveManagedArtifact(slf4jGroupId, "log4j-over-slf4j", TYPE_JAR));

        // When writing functional tests there's is often the need to export pages as XAR. Thus, in order to make
        // developer's life easy, we also include the filter module (used for XAR exports).
        mandatoryTopLevelArtifacts.add(this.repositorySystem.createArtifact(XWIKI_PLATFORM_GROUPID,
            "xwiki-platform-filter-instance-oldcore", this.xwikiVersion, null, TYPE_JAR));

        return mandatoryTopLevelArtifacts;
    }

    private Set<Artifact> resolveTransitively(Set<Artifact> artifacts) throws MojoExecutionException
    {
        AndArtifactFilter filter =
            new AndArtifactFilter(Arrays.asList(new ScopeArtifactFilter("runtime"),
                // - Exclude JCL and LOG4J since we want all logging to go through SLF4J. Note that we're excluding
                // log4j-<version>.jar but keeping log4j-over-slf4j-<version>.jar
                // - Exclude batik-js to prevent conflict with the patched version of Rhino used by yuicompressor used
                // for JSX. See http://jira.xwiki.org/jira/browse/XWIKI-6151 for more details.
                new ExcludesArtifactFilter(Arrays.asList("org.apache.xmlgraphic:batik-js",
                    "commons-logging:commons-logging", "commons-logging:commons-logging-api", "log4j:log4j"))));

        ArtifactResolutionRequest request =
            new ArtifactResolutionRequest().setArtifact(this.project.getArtifact()).setArtifactDependencies(artifacts)
                .setCollectionFilter(filter).setRemoteRepositories(this.remoteRepositories)
                .setLocalRepository(this.localRepository).setManagedVersionMap(getManagedVersionMap())
                .setResolveRoot(false);
        ArtifactResolutionResult resolutionResult = this.repositorySystem.resolve(request);
        if (resolutionResult.hasExceptions()) {
            throw new MojoExecutionException(String.format("Failed to resolve artifacts [%s]", artifacts,
                resolutionResult.getExceptions().get(0)));
        }

        return resolutionResult.getArtifacts();
    }

    private Map<String, Artifact> getManagedVersionMap() throws MojoExecutionException
    {
        Map<String, Artifact> dependencyManagementMap = new HashMap<String, Artifact>();

        // Manually add the top level <dependencyManagement> since this is where we keep all our dependencies management
        // information. We absolutely need to include those because Maven 3.x's artifact seems to have a big hole in
        // not handling artifact's parent dependency management information by itself!
        // See http://jira.codehaus.org/browse/MNG-5462
        dependencyManagementMap.putAll(getTopLevelPOMProject().getManagedVersionMap());

        // We add the project's dependency management in a second step so that it can override the platform dep mgmt map
        dependencyManagementMap.putAll(this.project.getManagedVersionMap());

        return dependencyManagementMap;
    }

    private MavenProject getTopLevelPOMProject() throws MojoExecutionException
    {
        return MavenUtils.getMavenProject(
            this.repositorySystem.createProjectArtifact(PHENOTIPS_GROUPID, "phenotips-parent",
                this.project.getVersion()), this.session, this.projectBuilder);
    }

    private String getDependencyManagementVersion(MavenProject project, String groupId, String artifactId)
        throws MojoExecutionException
    {
        for (Object dependencyObject : project.getDependencyManagement().getDependencies()) {
            Dependency dependency = (Dependency) dependencyObject;
            if (dependency.getGroupId().equals(groupId) && dependency.getArtifactId().equals(artifactId)) {
                return dependency.getVersion();
            }
        }
        throw new MojoExecutionException(String.format("Failed to find artifact [%s:%s] in dependency management "
            + "for [%s]", groupId, artifactId, project.toString()));
    }

    private void resolveArtifact(Artifact artifact) throws MojoExecutionException
    {
        ArtifactResolutionRequest request =
            new ArtifactResolutionRequest().setArtifact(artifact).setRemoteRepositories(this.remoteRepositories)
                .setLocalRepository(this.localRepository);
        ArtifactResolutionResult resolutionResult = this.repositorySystem.resolve(request);
        if (resolutionResult.hasExceptions()) {
            throw new MojoExecutionException(String.format("Failed to resolve artifact [%s]", artifact,
                resolutionResult.getExceptions().get(0)));
        }
    }

    private Artifact resolveManagedArtifact(String groupId, String artifactId, String type)
        throws MojoExecutionException
    {
        String version = getDependencyManagementVersion(getTopLevelPOMProject(), groupId, artifactId);
        return resolveArtifact(groupId, artifactId, version, type);
    }

    private Artifact resolveArtifact(String groupId, String artifactId, String version, String type)
        throws MojoExecutionException
    {
        Artifact artifact = this.repositorySystem.createArtifact(groupId, artifactId, version, "", type);
        resolveArtifact(artifact);
        return artifact;
    }

    private VelocityContext createVelocityContext()
    {
        Properties properties = new Properties();
        properties.putAll(getDefaultConfigurationProperties());
        final Properties projectProperties = this.project.getProperties();
        for (Object key : projectProperties.keySet()) {
            properties.put(key.toString(), projectProperties.get(key).toString());
        }

        VelocityContext context = new VelocityContext(properties);

        String inceptionYear = this.project.getInceptionYear();
        String year = new SimpleDateFormat("yyyy").format(new Date());

        if (StringUtils.isEmpty(inceptionYear)) {
            inceptionYear = year;
        }
        context.put("project", this.project);
        context.put("presentYear", year);

        if (inceptionYear.equals(year)) {
            year = inceptionYear + "-" + year;
        }
        context.put("projectTimespan", year);

        return context;
    }

    private Properties getDefaultConfigurationProperties()
    {
        Properties props = new Properties();

        // Default configuration data for hibernate.cfg.xml
        props.setProperty("xwikiDbConnectionUrl",
            "jdbc:hsqldb:file:${environment.permanentDirectory}/database/xwiki_db;shutdown=true");
        props.setProperty("xwikiDbConnectionUsername", "sa");
        props.setProperty("xwikiDbConnectionPassword", "");
        props.setProperty("xwikiDbConnectionDriverClass", "org.hsqldb.jdbcDriver");
        props.setProperty("xwikiDbDialect", "org.hibernate.dialect.HSQLDialect");
        props.setProperty("xwikiDbHbmXwiki", "xwiki.hbm.xml");
        props.setProperty("xwikiDbHbmFeeds", "feeds.hbm.xml");

        // Default configuration data for xwiki.cfg
        props.setProperty("xwikiCfgPlugins", "com.xpn.xwiki.plugin.skinx.JsSkinExtensionPlugin,\\"
            + "        com.xpn.xwiki.plugin.skinx.JsSkinFileExtensionPlugin,\\"
            + "        com.xpn.xwiki.plugin.skinx.CssSkinExtensionPlugin,\\"
            + "        com.xpn.xwiki.plugin.skinx.CssSkinFileExtensionPlugin,\\"
            + "        com.xpn.xwiki.plugin.skinx.LinkExtensionPlugin");
        props.setProperty("xwikiCfgVirtualUsepath", "1");
        props.setProperty("xwikiCfgEditCommentMandatory", "0");
        props.setProperty("xwikiCfgDefaultSkin", this.defaultSkin);
        props.setProperty("xwikiCfgDefaultBaseSkin", this.defaultSkin);
        props.setProperty("xwikiCfgEncoding", "UTF-8");

        // Other default configuration properties
        try {
            props.setProperty("xwikiDataDir", this.dataDirectory.getCanonicalPath());
        } catch (IOException e) {
            // Shouldn't happen
        }

        return props;
    }
}
