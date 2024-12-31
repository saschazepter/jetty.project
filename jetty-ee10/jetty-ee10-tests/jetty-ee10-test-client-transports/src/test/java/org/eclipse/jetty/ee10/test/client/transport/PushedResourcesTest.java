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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushedResourcesTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResources(TransportType transportType) throws Exception
    {
        Random random = new Random();
        byte[] bytes = new byte[512];
        random.nextBytes(bytes);
        byte[] pushBytes1 = new byte[1024];
        random.nextBytes(pushBytes1);
        byte[] pushBytes2 = new byte[2048];
        random.nextBytes(pushBytes2);

        String path1 = "/secondary1";
        String path2 = "/secondary2";
        start(transportType, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Cookie cookie0 = new Cookie("C0", "v0");
                cookie0.setMaxAge(0);
                response.addCookie(cookie0);
                Cookie cookie1 = new Cookie("C1", "v1");
                response.addCookie(cookie1);

                String target = request.getRequestURI();
                if (target.equals(path1))
                {
                    assertThat(request.getHeader("H1"), Matchers.equalTo("V1"));
                    assertThat(request.getHeader("Cookie"), Matchers.equalTo("C1=v1"));
                    response.getOutputStream().write(pushBytes1);
                }
                else if (target.equals(path2))
                {
                    assertThat(request.getHeader("H1"), Matchers.nullValue());
                    assertThat(request.getHeader("Cookie"), Matchers.equalTo("C1=v1"));
                    response.getOutputStream().write(pushBytes2);
                }
                else
                {
                    assertThat(request.getHeader("Cookie"), Matchers.equalTo("C0=toBeRemoved"));
                    request.newPushBuilder()
                        .path(path1)
                        .addHeader("H1", "V1")
                        .push();
                    request.newPushBuilder()
                        .path(path2)
                        .push();
                    response.getOutputStream().write(bytes);
                }
            }
        });

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        ContentResponse response = client.newRequest(newURI(transportType))
            .headers(h -> h.add("Cookie", "C0=toBeRemoved"))
            .onPush((mainRequest, pushedRequest) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    if (pushedRequest.getPath().equals(path1))
                    {
                        assertArrayEquals(pushBytes1, getContent());
                        latch1.countDown();
                    }
                    else if (pushedRequest.getPath().equals(path2))
                    {
                        assertArrayEquals(pushBytes2, getContent());
                        latch2.countDown();
                    }
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(bytes, response.getContent());
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResourceRedirect(TransportType transportType) throws Exception
    {
        Random random = new Random();
        byte[] pushBytes = new byte[512];
        random.nextBytes(pushBytes);

        String oldPath = "/old";
        String newPath = "/new";
        start(transportType, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String target = request.getRequestURI();
                if (target.equals(oldPath))
                    response.sendRedirect(newPath);
                else if (target.equals(newPath))
                    response.getOutputStream().write(pushBytes);
                else
                    request.newPushBuilder().path(oldPath).push();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        ;
        ContentResponse response = client.newRequest(newURI(transportType))
            .onPush((mainRequest, pushedRequest) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(oldPath, pushedRequest.getPath());
                    assertEquals(newPath, result.getRequest().getPath());
                    assertArrayEquals(pushBytes, getContent());
                    latch.countDown();
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
