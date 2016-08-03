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

import org.openqa.selenium.WebDriver;

/**
 * Creates and authenticates a "John Doe" user in the wiki before the test starts.
 *
 * @version $Id$
 * @since 1.0M3
 */
public class JDoeAuthenticationRule extends AuthenticationRule
{
    public JDoeAuthenticationRule(TestUtils testUtils, WebDriver driver, boolean autologin)
    {
        super("jdoe", "aaaaaa", testUtils, driver, autologin, "first_name", "John", "last_name", "Doe");
    }
}
