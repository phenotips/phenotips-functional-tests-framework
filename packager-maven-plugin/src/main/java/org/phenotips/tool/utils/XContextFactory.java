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

import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextException;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.model.reference.DocumentReference;

import java.io.File;
import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.HSQLDialect;
import org.hibernate.jdbc.Work;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiConfig;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.store.XWikiCacheStore;
import com.xpn.xwiki.store.XWikiHibernateBaseStore.HibernateCallback;
import com.xpn.xwiki.store.XWikiHibernateStore;
import com.xpn.xwiki.store.XWikiStoreInterface;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.web.XWikiServletRequestStub;
import com.xpn.xwiki.web.XWikiServletURLFactory;

/**
 * Creates and disposes old XWikiContext objects.
 *
 * @version $Id$
 * @since 1.0
 */
public final class XContextFactory
{
    private XContextFactory()
    {
        // Forbid instantiation of utility class
    }

    /**
     * Create a new XWikiContext configured with a specific database connection.
     *
     * @param databaseName the schema or database name to connect to
     * @param hibernateConfig the Hibernate configuration file containing the database connection definition (JDBC
     *            driver, username and password, etc)
     * @return a valid XWikiContext using the passed Hibernate configuration
     * @throws Exception failed to initialize context
     */
    public static XWikiContext createXWikiContext(String databaseName, File hibernateConfig) throws Exception
    {
        return createXWikiContext(databaseName, hibernateConfig, null);
    }

    /**
     * Create a new XWikiContext configured with a specific database connection.
     *
     * @param databaseName the schema or database name to connect to
     * @param hibernateConfig the Hibernate configuration file containing the database connection definition (JDBC
     *            driver, username and password, etc)
     * @param xwikiConfig an optional {@code xwiki.cfg} file to use as the XWiki configuration
     * @return a valid XWikiContext using the passed Hibernate configuration
     * @throws Exception failed to initialize context
     */
    public static XWikiContext createXWikiContext(String databaseName, File hibernateConfig, File xwikiConfig)
        throws Exception
    {
        // Initialize the Component Manager and Environment
        ComponentManager cm = org.xwiki.environment.System.initialize();
        Utils.setComponentManager(cm);

        XWikiContext xcontext = new XWikiContext();
        xcontext.put(ComponentManager.class.getName(), cm);

        // Initialize the Container fields (request, response, session).
        ExecutionContextManager ecim = cm.getInstance(ExecutionContextManager.class);
        try {
            ExecutionContext econtext = new ExecutionContext();

            // Bridge with old XWiki Context, required for old code.
            xcontext.declareInExecutionContext(econtext);

            ecim.initialize(econtext);
        } catch (ExecutionContextException e) {
            throw new Exception("Failed to initialize Execution Context.", e);
        }

        xcontext.setDatabase(databaseName);
        xcontext.setMainXWiki(databaseName);

        // Use a dummy Request even in daemon mode so that XWiki's initialization can create a Servlet URL Factory.
        xcontext.setRequest(new XWikiServletRequestStub());

        // Use a dummy URL so that XWiki's initialization can create a Servlet URL Factory. We could also have
        // registered a custom XWikiURLFactory against XWikiURLFactoryService but it's more work.
        xcontext.setURL(new URL("http://localhost/xwiki/bin/DummyAction/DumySpace/DummyPage"));

        // Set a dummy Document in the context to act as the current document since when a document containing
        // objects is imported it'll generate Object diff events and the algorithm to compute an object diff
        // currently requires rendering object properties, which requires a current document in the context.
        xcontext.setDoc(new XWikiDocument(new DocumentReference(databaseName, "dummySpace", "dummyPage")));

        XWikiConfig config;
        if (xwikiConfig != null && xwikiConfig.isFile() && xwikiConfig.canRead()) {
            config = new XWikiConfig(new FileInputStream(xwikiConfig));
        } else {
            config = new XWikiConfig();
        }
        config.put("xwiki.store.class", "com.xpn.xwiki.store.XWikiHibernateStore");

        // The XWikiConfig object requires path to be in unix format (i.e. with forward slashes)
        String hibernateConfigInUnixFormat = hibernateConfig.getPath().replace('\\', '/');
        config.put("xwiki.store.hibernate.path", hibernateConfigInUnixFormat);

        config.put("xwiki.store.hibernate.updateschema", "1");

        // Enable backlinks so that when documents are imported their backlinks will be saved too
        config.put("xwiki.backlinks", "1");

        XWiki xwiki = new XWiki(config, xcontext, null, true);

        xcontext.setUserReference(new DocumentReference("xwiki", "XWiki", "superadmin"));

        try {
            xcontext.setURLFactory(new XWikiServletURLFactory(new URL("http://localhost:8080"), "xwiki/", "bin/"));
        } catch (MalformedURLException e) {
            // Not really going to happen, that's a valid URL
        }

        // Trigger extensions that need to initialize the database (create classes, run migrators, etc.)
        xwiki.updateDatabase(xcontext.getMainXWiki(), xcontext);

        return xcontext;
    }

    /**
     * Free resources initialized by {@link #createXWikiContext(String, File)}.
     *
     * @param xcontext the XWiki context
     * @throws ComponentLookupException when failing to dispose component manager
     */
    public static void disposeXWikiContext(XWikiContext xcontext) throws ComponentLookupException
    {
        shutdownHSQLDB(xcontext);
        @SuppressWarnings("deprecation")
        ComponentManager componentManager = Utils.getComponentManager();

        // Remove ExecutionContext
        Execution execution = componentManager.getInstance(Execution.class);
        execution.removeContext();

        // Dispose component manager
        org.xwiki.environment.System.dispose(componentManager);

        Utils.setComponentManager(null);
    }

    /**
     * Shuts down HSQLDB.
     *
     * @param context the XWiki Context object from which we can retrieve the Store implementation
     */
    private static void shutdownHSQLDB(XWikiContext context)
    {
        XWikiStoreInterface store = context.getWiki().getStore();
        if (XWikiCacheStore.class.isAssignableFrom(store.getClass())) {
            store = ((XWikiCacheStore) store).getStore();
        }

        if (XWikiHibernateStore.class.isAssignableFrom(store.getClass())) {
            XWikiHibernateStore hibernateStore = (XWikiHibernateStore) store;

            // check that is HSQLDB
            Dialect dialect = Dialect.getDialect(hibernateStore.getConfiguration().getProperties());
            if (!(dialect instanceof HSQLDialect)) {
                return;
            }

            try {
                hibernateStore.checkHibernate(context);
                hibernateStore.executeRead(context, new HibernateCallback<Object>()
                {
                    @Override
                    public Object doInHibernate(Session session) throws HibernateException, XWikiException
                    {
                        session.doWork(new Work()
                        {
                            @Override
                            public void execute(Connection connection) throws SQLException
                            {
                                Statement stmt = connection.createStatement();
                                stmt.execute("SHUTDOWN");
                            }
                        });
                        return null;
                    }
                });
            } catch (Exception e) {
                // This shouldn't matter so much
            }
        }
    }
}
