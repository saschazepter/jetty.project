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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WriteTooMuchForContentLengthTest
{
    Server server;
    LocalConnector localConnector;

    private void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        server.setHandler(contextHandler);
        server.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testWriteExcessive() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet testServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setStatus(200);
                resp.setContentLength(100);
                byte[] buf = new byte[2000];
                Arrays.fill(buf, (byte)'x');
                resp.getOutputStream().write(buf);
            }
        };
        ServletHolder holder = new ServletHolder(testServlet);
        contextHandler.addServlet(holder, "/test/*");

        startServer(contextHandler);

        String rawRequest = "GET /test/ HTTP/1.1\r\n"
            + "Host: local\r\n"
            + "Connection: close\r\n"
            + "\r\n";

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("status", response.getStatus(), is(200));
        assertThat("content-length", response.get(HttpHeader.CONTENT_LENGTH), is("100"));
        String body = response.getContent();
        assertThat("body.length()", body.length(), is(100));
    }
}
