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
package org.phenotips.tool.xarimporter;

import org.phenotips.tool.utils.XContextFactory;

import org.xwiki.model.reference.DocumentReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.commons.io.IOUtils;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.plugin.packaging.DocumentInfo;
import com.xpn.xwiki.plugin.packaging.Package;
import com.xpn.xwiki.plugin.packaging.PackageException;

/**
 * Import a set of XWiki documents into an existing database.
 *
 * @version $Id$
 */
public class Importer
{
    /**
     * Import documents defined in an XML file located in the passed document definition directory into a database
     * defined by its passed name and by an Hibernate configuration file.
     *
     * @param sourceDirectory the directory where the package.xml file is located and where the documents to import are
     *            located
     * @param databaseName some database name (TODO: find out what this name is really)
     * @param hibernateConfig the Hibernate config fill containing the database definition (JDBC driver, username and
     *            password, etc)
     * @throws Exception if the import failed for any reason
     * @todo Replace the Hibernate config file with a list of parameters required for the importation
     */
    public void importDocuments(File sourceDirectory, String databaseName, File hibernateConfig) throws Exception
    {
        importDocuments(sourceDirectory, databaseName, hibernateConfig, null, null);
    }

    /**
     * Import documents defined in an XML file located in the passed document definition directory into a database
     * defined by its passed name and by an Hibernate configuration file.
     *
     * @param sourceDirectory the directory where the package.xml file is located and where the documents to import are
     *            located
     * @param databaseName some database name (TODO: find out what this name is really)
     * @param hibernateConfig the Hibernate config fill containing the database definition (JDBC driver, username and
     *            password, etc)
     * @param xwikiConfig an optional {@code xwiki.cfg} file to use as the XWiki configuration
     * @throws Exception if the import failed for any reason
     * @todo Replace the Hibernate config file with a list of parameters required for the importation
     */
    public void importDocuments(File sourceDirectory, String databaseName, File hibernateConfig, File xwikiConfig)
        throws Exception
    {
        importDocuments(sourceDirectory, databaseName, hibernateConfig, xwikiConfig, null);
    }

    /**
     * Import documents defined in an XML file located in the passed document definition directory into a database
     * defined by its passed name and by an Hibernate configuration file.
     *
     * @param sourceDirectory the directory where the package.xml file is located and where the documents to import are
     *            located
     * @param databaseName some database name (TODO: find out what this name is really)
     * @param hibernateConfig the Hibernate config fill containing the database definition (JDBC driver, username and
     *            password, etc)
     * @param xwikiConfig an optional {@code xwiki.cfg} file to use as the XWiki configuration
     * @param importUser optionally the user under which to perform the import (useful for example when importing pages
     *            that need to have Programming Rights and the page author is not the same as the importing user)
     * @throws Exception if the import failed for any reason
     * @todo Replace the Hibernate config file with a list of parameters required for the importation
     */
    public void importDocuments(File sourceDirectory, String databaseName, File hibernateConfig, File xwikiConfig,
        String importUser) throws Exception
    {
        XWikiContext xcontext = XContextFactory.createXWikiContext(databaseName, hibernateConfig, xwikiConfig);

        Package pack = new Package();
        pack.setWithVersions(false);

        try {
            pack.readFromDir(sourceDirectory, xcontext);
        } catch (IOException e) {
            throw new PackageException(PackageException.ERROR_PACKAGE_UNKNOWN, "Failed to import documents from ["
                + sourceDirectory + "]", e);
        }
        installWithUser(importUser, pack, xcontext);

        XContextFactory.disposeXWikiContext(xcontext);
    }

    /**
     * @param file the XAR file to import
     * @param importUser optionally the user under which to perform the import (useful for example when importing pages
     *            that need to have Programming Rights and the page author is not the same as the importing user)
     * @param context the XWiki context
     * @return the number of imported documents
     * @throws XWikiException failed to import the XAR file
     * @throws IOException failed to parse the XAR file
     */
    public int importXAR(File file, String importUser, XWikiContext context) throws XWikiException, IOException
    {
        Package pack = new Package();
        pack.setWithVersions(false);

        // Parse XAR
        FileInputStream fis = new FileInputStream(file);
        try {
            pack.Import(fis, context);
        } finally {
            IOUtils.closeQuietly(fis);
        }

        // Import into the database
        if (!pack.getFiles().isEmpty()) {
            installWithUser(importUser, pack, context);
        }

        return pack.getFiles().size();
    }

    /**
     * Install a Package as a backup pack or with the passed user (if any).
     *
     * @param importUser the user to import with or null if it should be imported as a backup pack
     * @param pack the Package instance performing the import
     * @param context the XWiki Context
     * @throws XWikiException if the import failed for any reason
     */
    private void installWithUser(String importUser, Package pack, XWikiContext context) throws XWikiException
    {
        // Set the current context user if an import user is specified (i.e. not null)
        DocumentReference currentUserReference = context.getUserReference();
        if (importUser != null) {
            pack.setBackupPack(false);
            // Set the current user in the context to the import user
            context.setUserReference(new DocumentReference("xwiki", "XWiki", importUser));
        }

        try {
            int code = pack.install(context);
            if (code != DocumentInfo.INSTALL_OK) {
                throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN,
                    "Failed to import XAR with code [" + code + "]");
            }
        } finally {
            // Restore context user as before
            context.setUserReference(currentUserReference);
        }
    }
}
