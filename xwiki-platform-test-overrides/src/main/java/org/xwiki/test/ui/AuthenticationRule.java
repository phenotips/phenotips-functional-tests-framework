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
package org.xwiki.test.ui;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.openqa.selenium.WebDriver;

/**
 * Authenticates a user in the wiki before the test starts.
 *
 * @version $Id$
 * @since 5.1M1
 * @todo decide if we want this way of authenticating to replace {@link AbstractStandardUserAuthenticatedTest}
 */
public class AuthenticationRule implements TestRule
{
    protected String userName;

    protected String userPassword;

    protected TestUtils testUtils;

    protected WebDriver driver;

    protected boolean autologin;

    protected Object[] parameters;

    public AuthenticationRule(String userName, String userPassword, TestUtils testUtils, WebDriver driver)
    {
        this(userName, userPassword, testUtils, driver, true);
    }

    public AuthenticationRule(String userName, String userPassword, TestUtils testUtils, WebDriver driver,
        boolean autologin, Object... parameters)
    {
        this.userName = userName;
        this.userPassword = userPassword;
        this.testUtils = testUtils;
        this.driver = driver;
        this.autologin = autologin;
        this.parameters = parameters;
    }

    @Override
    public Statement apply(final Statement base, final Description description)
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                registerIfNeeded();
                if (AuthenticationRule.this.autologin) {
                    authenticate();
                }
                base.evaluate();
            }
        };
    }

    public void authenticate()
    {
        if (!this.userName.equals(this.testUtils.getLoggedInUserName())) {
            // Log in and direct to a non existent page so that it loads very fast and we don't incur the time cost of
            // going to the home page for example.
            this.driver.get(this.testUtils.getURLToLoginAndGotoPage(this.userName, this.userPassword,
                this.testUtils.getURLToNonExistentPage()));
            this.testUtils.recacheSecretToken();
        }
    }

    public void registerIfNeeded()
    {
        if (!"superadmin".equals(this.userName) && !"XWikiGuest".equals(this.userName)
            && !this.testUtils.pageExists("XWiki", this.userName)) {
            this.testUtils.createUserAndLogin(this.userName, this.userPassword, this.parameters);
        }
    }
}
