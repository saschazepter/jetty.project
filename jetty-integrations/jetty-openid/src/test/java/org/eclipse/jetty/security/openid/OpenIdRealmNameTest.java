//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.openid;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class OpenIdRealmNameTest
{
    private final Server server = new Server();

    public static SecurityHandler configureOpenIdContext(String realmName)
    {
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));
        securityHandler.setAuthenticationType(Authenticator.OPENID_AUTH);
        securityHandler.setRealmName(realmName);
        return securityHandler;
    }

    @Test
    public void testSingleConfiguration() throws Exception
    {
        // Add some OpenID configurations.
        OpenIdConfiguration config1 = new OpenIdConfiguration.Builder("provider1", "", "")
            .authorizationEndpoint("test")
            .tokenEndpoint("test")
            .build();
        server.addBean(config1);

        // Configure two webapps to select configs based on realm name.
        SecurityHandler context1 = configureOpenIdContext("This doesn't matter if only 1 OpenIdConfiguration");
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        server.setHandler(contextHandlerCollection);

        try
        {
            server.start();

            // The OpenIdConfiguration from context1 matches to config1.
            Authenticator authenticator = context1.getAuthenticator();
            assertThat(authenticator, instanceOf(OpenIdAuthenticator.class));
            LoginService loginService = ((OpenIdAuthenticator)authenticator).getLoginService();
            assertThat(loginService, instanceOf(OpenIdLoginService.class));
            assertThat(((OpenIdLoginService)loginService).getConfiguration(), Matchers.is(config1));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSingleConfigurationNoRealmName() throws Exception
    {
        // Add some OpenID configurations.
        OpenIdConfiguration config1 = new OpenIdConfiguration.Builder("provider1", "", "")
            .authorizationEndpoint("test")
            .tokenEndpoint("test")
            .build();
        server.addBean(config1);

        // Configure two webapps to select configs based on realm name.
        SecurityHandler context1 = configureOpenIdContext(null);
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        server.setHandler(contextHandlerCollection);

        try
        {
            server.start();

            // The OpenIdConfiguration from context1 matches to config1.
            Authenticator authenticator = context1.getAuthenticator();
            assertThat(authenticator, instanceOf(OpenIdAuthenticator.class));
            LoginService loginService = ((OpenIdAuthenticator)authenticator).getLoginService();
            assertThat(loginService, instanceOf(OpenIdLoginService.class));
            assertThat(((OpenIdLoginService)loginService).getConfiguration(), Matchers.is(config1));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testMultipleConfiguration() throws Exception
    {
        // Add some OpenID configurations.
        OpenIdConfiguration config1 = new OpenIdConfiguration.Builder("provider1", "", "")
            .authorizationEndpoint("test")
            .tokenEndpoint("test")
            .build();
        OpenIdConfiguration config2 = new OpenIdConfiguration.Builder("provider2", "", "")
            .authorizationEndpoint("test")
            .tokenEndpoint("test")
            .build();
        server.addBean(config1);
        server.addBean(config2);

        // Configure two webapps to select configs based on realm name.
        SecurityHandler context1 = configureOpenIdContext(config1.getIssuer());
        SecurityHandler context2 = configureOpenIdContext(config2.getIssuer());
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        contextHandlerCollection.addHandler(context2);
        server.setHandler(contextHandlerCollection);

        try
        {
            server.start();

            // The OpenIdConfiguration from context1 matches to config1.
            Authenticator authenticator = context1.getAuthenticator();
            assertThat(authenticator, instanceOf(OpenIdAuthenticator.class));
            LoginService loginService = ((OpenIdAuthenticator)authenticator).getLoginService();
            assertThat(loginService, instanceOf(OpenIdLoginService.class));
            assertThat(((OpenIdLoginService)loginService).getConfiguration(), Matchers.is(config1));

            // The OpenIdConfiguration from context2 matches to config2.
            authenticator = context2.getAuthenticator();
            assertThat(authenticator, instanceOf(OpenIdAuthenticator.class));
            loginService = ((OpenIdAuthenticator)authenticator).getLoginService();
            assertThat(loginService, instanceOf(OpenIdLoginService.class));
            assertThat(((OpenIdLoginService)loginService).getConfiguration(), Matchers.is(config2));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testMultipleConfigurationNoMatch() throws Exception
    {
        // Add some OpenID configurations.
        OpenIdConfiguration config1 = new OpenIdConfiguration.Builder("provider1", "", "").build();
        OpenIdConfiguration config2 = new OpenIdConfiguration.Builder("provider2", "", "").build();
        server.addBean(config1);
        server.addBean(config2);

        // Configure two webapps to select configs based on realm name.
        SecurityHandler context1 = configureOpenIdContext("provider3");
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        server.setHandler(contextHandlerCollection);

        // Multiple OpenIdConfigurations were available and didn't match one based on realm name.
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    public void testNoConfiguration() throws Exception
    {
        SecurityHandler context1 = configureOpenIdContext(null);
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        server.setHandler(contextHandlerCollection);

        // If no OpenIdConfigurations are present it is bad configuration.
        assertThrows(IllegalStateException.class, server::start);
    }
}
