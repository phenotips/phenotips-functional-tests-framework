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
package org.phenotips.tool.xarimporter;

import org.phenotips.tool.utils.LogUtils;
import org.phenotips.tool.utils.MavenUtils;
import org.phenotips.tool.utils.XContextFactory;

import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.extension.DefaultExtensionAuthor;
import org.xwiki.extension.DefaultExtensionDependency;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.ExtensionLicense;
import org.xwiki.extension.ExtensionLicenseManager;
import org.xwiki.extension.InstallException;
import org.xwiki.extension.LocalExtension;
import org.xwiki.extension.ResolveException;
import org.xwiki.extension.repository.InstalledExtensionRepository;
import org.xwiki.extension.repository.LocalExtensionRepository;
import org.xwiki.extension.repository.LocalExtensionRepositoryException;
import org.xwiki.extension.repository.internal.local.DefaultLocalExtension;
import org.xwiki.extension.version.internal.DefaultVersionConstraint;
import org.xwiki.properties.ConverterManager;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.hibernate.cfg.Environment;

import com.xpn.xwiki.XWikiContext;

/**
 * Maven plugin for importing a set of XWiki documents into a database.
 *
 * @version $Id$
 * @since 1.0M1
 */
@Mojo(
    name = "import",
    requiresDependencyResolution = ResolutionScope.COMPILE)
public class ImportMojo extends AbstractMojo
{
    private static final String MPKEYPREFIX = "xwiki.extension.";

    private static final String MPNAME_NAME = "name";

    private static final String MPNAME_SUMMARY = "summary";

    private static final String MPNAME_WEBSITE = "website";

    private static final String MPNAME_FEATURES = "features";

    /** @see org.phenotips.tool.xarimporter.Importer#importDocuments(java.io.File, String, java.io.File) */
    @Parameter(defaultValue = "xwiki")
    private String databaseName;

    /** @see org.phenotips.tool.xarimporter.Importer#importDocuments(java.io.File, String, java.io.File) */
    @Parameter(defaultValue = "${basedir}/src/main/packager/hibernate.cfg.xml")
    private File hibernateConfig;

    /** @see org.phenotips.tool.xarimporter.Importer#importDocuments(java.io.File, String, java.io.File) */
    @Parameter(defaultValue = "${basedir}/src/main/packager/xwiki.cfg")
    private File xwikiConfig;

    /** @see org.phenotips.tool.xarimporter.Importer#importDocuments(java.io.File, String, java.io.File) */
    @Parameter
    private File sourceDirectory;

    /** @see org.phenotips.tool.xarimporter.Importer#importDocuments(java.io.File, String, java.io.File) */
    @Parameter(defaultValue = "${project.build.directory}/data/")
    private File xwikiDataDir;

    /** The project being built. */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /** Builds a Model from a pom.xml. */
    @Component
    private ProjectBuilder projectBuilder;

    /** The current Maven session being executed. */
    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        LogUtils.configureXWikiLogs();
        Importer importer = new Importer();

        System.setProperty("xwiki.data.dir", this.xwikiDataDir.getAbsolutePath());
        // If the package mojo was executed before, it might have left a different database connection URL in the
        // environment, which apparently overrides the value in the configuration file
        System.clearProperty(Environment.URL);

        if (this.sourceDirectory != null) {
            try {
                importer.importDocuments(this.sourceDirectory, this.databaseName, this.hibernateConfig,
                    this.xwikiConfig);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to import XWiki documents", e);
            }
        } else {
            try {
                importDependencies(importer, this.databaseName, this.hibernateConfig, this.xwikiConfig);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to import XAR dependencies", e);
            }
        }
    }

    /**
     * @param importer the importer
     * @param databaseName some database name (TODO: find out what this name is really)
     * @param hibernateConfig the Hibernate config fill containing the database definition (JDBC driver, username and
     *            password, etc)
     * @throws Exception failed to import dependencies
     */
    private void importDependencies(Importer importer, String databaseName, File hibernateConfig, File xwikiConfig)
        throws Exception
    {
        XWikiContext xcontext = XContextFactory.createXWikiContext(databaseName, hibernateConfig, xwikiConfig);

        // Reverse artifact order to have dependencies first (despite the fact that it's a Set it's actually an ordered
        // LinkedHashSet behind the scene)
        List<Artifact> dependenciesFirstArtifacts = new ArrayList<Artifact>(this.project.getArtifacts());
        Collections.reverse(dependenciesFirstArtifacts);

        for (Artifact artifact : dependenciesFirstArtifacts) {
            if (!artifact.isOptional()) {
                if ("xar".equals(artifact.getType())) {
                    getLog().info("  ... Importing XAR file: " + artifact.getFile());

                    // Import XAR into database
                    int nb = importer.importXAR(artifact.getFile(), null, xcontext);

                    getLog().info("  ..... Imported " + nb + " documents");

                    // Install extension
                    installExtension(artifact, xcontext);
                }
            }
        }

        XContextFactory.disposeXWikiContext(xcontext);
    }

    private void installExtension(Artifact artifact, XWikiContext xcontext) throws ComponentLookupException,
        InstallException, LocalExtensionRepositoryException, MojoExecutionException, ResolveException
    {
        ComponentManager componentManager = (ComponentManager) xcontext.get(ComponentManager.class.getName());

        LocalExtensionRepository localExtensionRepository =
            componentManager.getInstance(LocalExtensionRepository.class);
        InstalledExtensionRepository installedExtensionRepository =
            componentManager.getInstance(InstalledExtensionRepository.class);

        DefaultLocalExtension extension =
            new DefaultLocalExtension(null, new ExtensionId(artifact.getGroupId() + ':' + artifact.getArtifactId(),
                artifact.getBaseVersion()), artifact.getType());

        extension.setFile(artifact.getFile());

        MavenProject artifactProject = MavenUtils.getMavenProject(artifact, this.session, this.projectBuilder);

        toExtension(extension, artifactProject.getModel(), componentManager);

        if (localExtensionRepository.exists(extension.getId())) {
            localExtensionRepository.removeExtension(localExtensionRepository.getLocalExtension(extension.getId()));
        }
        LocalExtension localExtension = localExtensionRepository.storeExtension(extension);
        installedExtensionRepository.installExtension(localExtension, "wiki:xwiki", true);
    }

    private void toExtension(DefaultLocalExtension extension, Model model, ComponentManager componentManager)
        throws ComponentLookupException
    {
        extension.setName(getPropertyString(model, MPNAME_NAME, model.getName()));
        extension.setSummary(getPropertyString(model, MPNAME_SUMMARY, model.getDescription()));
        extension.setWebsite(getPropertyString(model, MPNAME_WEBSITE, model.getUrl()));

        // authors
        for (Developer developer : model.getDevelopers()) {
            URL authorURL = null;
            if (developer.getUrl() != null) {
                try {
                    authorURL = new URL(developer.getUrl());
                } catch (MalformedURLException e) {
                    this.getLog().warn(
                        "Invalid URL for developer [" + developer.getId() + "]: [" + developer.getUrl() + "]");
                }
            }

            extension.addAuthor(new DefaultExtensionAuthor(StringUtils.defaultIfBlank(developer.getName(),
                developer.getId()), authorURL));
        }

        // licenses
        ExtensionLicenseManager licenseManager = componentManager.getInstance(ExtensionLicenseManager.class);
        for (License license : model.getLicenses()) {
            extension.addLicense(getExtensionLicense(license, licenseManager));
        }

        // features
        String featuresString = getProperty(model, MPNAME_FEATURES);
        if (StringUtils.isNotBlank(featuresString)) {
            featuresString = featuresString.replaceAll("[\r\n]", "");
            ConverterManager converter = componentManager.getInstance(ConverterManager.class);
            extension.setFeatures(converter.<Collection<String>>convert(List.class, featuresString));
        }

        // dependencies
        for (Dependency mavenDependency : model.getDependencies()) {
            if (!mavenDependency.isOptional()
                && ("compile".equals(mavenDependency.getScope()) || "runtime".equals(mavenDependency.getScope()))) {
                extension.addDependency(new DefaultExtensionDependency(mavenDependency.getGroupId() + ':'
                    + mavenDependency.getArtifactId(), new DefaultVersionConstraint(mavenDependency.getVersion())));
            }
        }
    }

    private String getProperty(Model model, String propertyName)
    {
        return model.getProperties().getProperty(MPKEYPREFIX + propertyName);
    }

    private String getPropertyString(Model model, String propertyName, String def)
    {
        return StringUtils.defaultString(getProperty(model, propertyName), def);
    }

    // TODO: download custom licenses content
    private ExtensionLicense getExtensionLicense(License license, ExtensionLicenseManager licenseManager)
    {
        if (license.getName() == null) {
            return new ExtensionLicense("noname", null);
        }

        return createLicenseByName(license.getName(), licenseManager);
    }

    private ExtensionLicense createLicenseByName(String name, ExtensionLicenseManager licenseManager)
    {
        ExtensionLicense extensionLicense = licenseManager.getLicense(name);

        return extensionLicense != null ? extensionLicense : new ExtensionLicense(name, null);
    }
}
