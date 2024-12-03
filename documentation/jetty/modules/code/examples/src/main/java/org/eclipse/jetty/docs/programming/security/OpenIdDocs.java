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

package org.eclipse.jetty.docs.programming.security;

import java.io.PrintStream;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.security.openid.OpenIdLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.security.Credential;

public class OpenIdDocs
{
    private static final String ISSUER = "";
    private static final String CLIENT_ID = "";
    private static final String CLIENT_SECRET = "";
    private static final String TOKEN_ENDPOINT = "";
    private static final String AUTH_ENDPOINT = "";
    private static final String END_SESSION_ENDPOINT = "";
    private static final String AUTH_METHOD = "";
    private static final HttpClient httpClient = new HttpClient();
    private static Server server = new Server();

    private OpenIdConfiguration openIdConfig;
    private SecurityHandler securityHandler;

    public void createConfigurationWithDiscovery()
    {
        // tag::createConfigurationWithDiscovery[]
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(ISSUER, CLIENT_ID, CLIENT_SECRET);
        // end::createConfigurationWithDiscovery[]
    }

    public void createConfiguration()
    {
        // tag::createConfiguration[]
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(ISSUER, TOKEN_ENDPOINT, AUTH_ENDPOINT, END_SESSION_ENDPOINT,
            CLIENT_ID, CLIENT_SECRET, AUTH_METHOD, httpClient);
        // end::createConfiguration[]
    }

    public void configureLoginService()
    {
        // tag::configureLoginService[]
        LoginService loginService = new OpenIdLoginService(openIdConfig);
        securityHandler.setLoginService(loginService);
        // end::configureLoginService[]
    }

    public void configureAuthenticator()
    {
        // tag::configureAuthenticator[]
        Authenticator authenticator = new OpenIdAuthenticator(openIdConfig, "/error");
        securityHandler.setAuthenticator(authenticator);
        // end::configureAuthenticator[]
    }

    @SuppressWarnings("unchecked")
    public void accessClaims()
    {
        Request request = new Request.Wrapper(null);

        // tag::accessClaims[]
        Map<String, Object> claims = (Map<String, Object>)request.getSession(true).getAttribute("org.eclipse.jetty.security.openid.claims");
        String userId = (String)claims.get("sub");

        Map<String, Object> response = (Map<String, Object>)request.getSession(true).getAttribute("org.eclipse.jetty.security.openid.response");
        String accessToken = (String)response.get("access_token");
        // tag::accessClaims[]
    }

    public void wrappedLoginService()
    {
        // tag::wrappedLoginService[]
        // Use the optional LoginService for Roles.
        LoginService wrappedLoginService = createWrappedLoginService();
        LoginService loginService = new OpenIdLoginService(openIdConfig, wrappedLoginService);
        // end::wrappedLoginService[]
    }

    private LoginService createWrappedLoginService()
    {
        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("admin", Credential.getCredential("password"), new String[]{"admin"});
        loginService.setUserStore(userStore);
        loginService.setName(ISSUER);
        return loginService;
    }

    public static void main(String[] args) throws Exception
    {
        new OpenIdDocs().combinedExample();
    }

    public void combinedExample() throws Exception
    {
        OpenIdProvider openIdProvider = new OpenIdProvider("my-client-id", "my-client-secret");
        openIdProvider.addRedirectUri("http://localhost:8080/j_security_check");
        openIdProvider.start();

        server = new Server(8080);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                PrintStream writer = new PrintStream(Content.Sink.asOutputStream(response));

                String pathInContext = Request.getPathInContext(request);
                if (pathInContext.startsWith("/error"))
                {
                    Fields parameters = Request.getParameters(request);
                    writer.println("error: " + parameters.get("error_description"));
                }
                else
                {
                    writer.println("hello world");
                }

                writer.close();
                callback.succeeded();
                return true;
            }
        });

        // To create an OpenIdConfiguration you can rely on discovery of the OIDC metadata.
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(
            "https://example.com/issuer",           // ISSUER
            "my-client-id",                         // CLIENT_ID
            "my-client-secret"                     // CLIENT_SECRET
        );

        // Or you can specify the full OpenID configuration manually.
        openIdConfig = new OpenIdConfiguration(
            "https://example.com/issuer",           // ISSUER
            "https://example.com/token",            // TOKEN_ENDPOINT
            "https://example.com/auth",             // AUTH_ENDPOINT
            "https://example.com/logout",           // END_SESSION_ENDPOINT
            "my-client-id",                         // CLIENT_ID
            "my-client-secret",                     // CLIENT_SECRET
            "client_secret_post",                   // AUTH_METHOD (e.g., client_secret_post, client_secret_basic)
            httpClient                              // HttpClient instance
        );

        openIdConfig = new OpenIdConfiguration(
            openIdProvider.getProvider(),
            openIdProvider.getClientId(),
            openIdProvider.getClientSecret()
        );

        // The specific security handler implementation will change depending on whether you are using EE8/EE9/EE10/EE11 or Jetty Core API.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        server.insertHandler(securityHandler);
        securityHandler.put("/*", Constraint.ANY_USER);

        // A nested LoginService is optional and used to specify known users with defined roles.
        // This can be any instance of LoginService and is not restricted to be a HashLoginService.
        HashLoginService nestedLoginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("admin", null, new String[]{"admin"});
        nestedLoginService.setUserStore(userStore);

        // An OpenIdLoginService should be used which can optionally wrap the nestedLoginService to support roles.
        LoginService loginService = new OpenIdLoginService(openIdConfig, nestedLoginService);
        securityHandler.setLoginService(loginService);

        // Configure an OpenIdAuthenticator.
        Authenticator authenticator = new OpenIdAuthenticator(openIdConfig,
            "/j_security_check", // The path where the OIDC provider redirects back to Jetty.
            "/error", // The error page where authentication errors are redirected.
            "/logoutRedirect" // After logout the user is redirected to this page.
        );
        securityHandler.setAuthenticator(authenticator);

        server.insertHandler(new SessionHandler());
        server.setDumpAfterStart(true);
        server.start();
        server.join();
    }
}