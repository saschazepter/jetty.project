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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
        connector = null;
    }

    @Test
    public void testEncodedSpace() throws Exception
    {
        String request = """
                GET /fo%6f%20bar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), containsString("httpURI.path=/fo%6f%20bar"));
        assertThat(response.getContent(), containsString("pathInContext=/foo%20bar"));
    }

    @Test
    public void testEncodedPath() throws Exception
    {
        String request = """
                GET /fo%6f%2fbar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    public static Stream<Arguments> getUriTests()
    {
        return Stream.of(
            Arguments.of(UriCompliance.DEFAULT, "/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://local/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://other/", 200, "other"),
            Arguments.of(UriCompliance.DEFAULT, "https://user@local/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local/", 200, "local"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local:port/", 400, "Bad Request"),
            Arguments.of(UriCompliance.LEGACY, "https://user@local:8080/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://user@local:8080/", 200, "local:8080"),
            Arguments.of(UriCompliance.DEFAULT, "https://user:password@local/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user:password@local/", 200, "local"),
            Arguments.of(UriCompliance.DEFAULT, "https://user@other/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user@other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.DEFAULT, "https://user:password@other/", 400, "Deprecated User Info"),
            Arguments.of(UriCompliance.LEGACY, "https://user:password@other/", 400, "Authority!=Host"),
            Arguments.of(UriCompliance.UNSAFE, "https://user:password@other/", 200, "other"),
            Arguments.of(UriCompliance.DEFAULT, "/%2F/", 400, "Ambiguous URI path separator"),
            Arguments.of(UriCompliance.UNSAFE, "/%2F/", 200, "local")
        );
    }

    @ParameterizedTest
    @MethodSource("getUriTests")
    public void testGETUris(UriCompliance compliance, String uri, int status, String content) throws Exception
    {
        server.stop();
        for (Connector connector: server.getConnectors())
        {
            HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (httpConnectionFactory != null)
            {
                HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                httpConfiguration.setUriCompliance(compliance);
                if (compliance == UriCompliance.UNSAFE)
                    httpConfiguration.setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
            }
        }

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String msg = String.format("authority=\"%s\"", request.getHttpURI().getAuthority());
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, msg, callback);
                return true;
            }
        });
        server.start();
        String request = """
                GET %s HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """.formatted(uri);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertThat(response.getStatus(), is(status));
        if (content != null)
        {
            if (status == 200)
                assertThat(response.getContent(), is("authority=\"%s\"".formatted(content)));
            else
                assertThat(response.getContent(), containsString(content));
        }
    }

    @Test
    public void testAmbiguousPathSep() throws Exception
    {
        server.stop();
        for (Connector connector: server.getConnectors())
        {
            HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (httpConnectionFactory != null)
            {
                HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                httpConfiguration.setUriCompliance(UriCompliance.from(
                    EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR)
                ));
            }
        }

        ContextHandler fooContext = new ContextHandler();
        fooContext.setContextPath("/foo");
        fooContext.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                String msg = String.format("pathInContext=\"%s\"", pathInContext);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, msg, callback);
                return true;
            }
        });
        server.setHandler(fooContext);
        server.start();
        String request = """
                GET /foo/zed%2Fbar HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), is("pathInContext=\"/zed%2Fbar\""));
    }

    @Test
    public void testConnectRequestURLSameAsHost() throws Exception
    {
        String request = """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: myhost:9999\r
                Connection: close\r
                \r
                """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("httpURI=http://myhost:9999/"));
        assertThat(responseBody, containsString("httpURI.path=/"));
        assertThat(responseBody, containsString("servername=myhost"));
    }

    @Test
    public void testConnectRequestURLDifferentThanHost() throws Exception
    {
        // per spec, "Host" is ignored if request-target is authority-form
        String request = """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: otherhost:8888\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("httpURI=http://myhost:9999/"));
        assertThat(responseBody, containsString("httpURI.path=/"));
        assertThat(responseBody, containsString("servername=myhost"));
    }

    /**
     * Test to ensure that response.write() will add Content-Length on HTTP/1.1 responses.
     */
    @Test
    public void testContentLengthNotSetOneWrites() throws Exception
    {
        final int bufferSize = 4096;
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                byte[] buf = new byte[bufferSize];
                Arrays.fill(buf, (byte)'x');

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                response.write(true, ByteBuffer.wrap(buf), Callback.NOOP);
                return true;
            }
        });
        server.start();

        String request = """
                GET /foo HTTP/1.1\r
                Host: local\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getLongField(HttpHeader.CONTENT_LENGTH), greaterThan(0L));
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(bufferSize));
    }

    /**
     * Test to ensure that multiple response.write() will use
     * Transfer-Encoding chunked on HTTP/1.1 responses.
     */
    @Test
    public void testContentLengthNotSetTwoWrites() throws Exception
    {
        final int bufferSize = 4096;
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                byte[] buf = new byte[bufferSize];
                Arrays.fill(buf, (byte)'x');

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

                ByteBuffer bbuf = ByteBuffer.wrap(buf);
                int half = bufferSize / 2;
                ByteBuffer halfBuf = bbuf.slice();
                halfBuf.limit(half);
                response.write(false, halfBuf, Callback.from(() ->
                {
                    bbuf.position(half);
                    response.write(true, bbuf, callback);
                }));
                return true;
            }
        });
        server.start();

        String request = """
                GET /foo HTTP/1.1\r
                Host: local\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertNull(response.getField(HttpHeader.CONTENT_LENGTH));
        assertThat(response.get(HttpHeader.TRANSFER_ENCODING), containsString("chunked"));
        String responseBody = response.getContent();
        assertThat(responseBody.length(), is(bufferSize));
    }

    /**
     * Test that multiple requests on the same connection with different cookies
     * do not bleed cookies.
     * 
     * @throws Exception if there is a problem
     */
    @Test
    public void testDifferentCookies() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                ByteArrayOutputStream buff = new ByteArrayOutputStream();
                List<HttpCookie> coreCookies = org.eclipse.jetty.server.Request.getCookies(request);

                if (coreCookies != null)
                {
                    for (HttpCookie c : coreCookies)
                        buff.writeBytes(("Core Cookie: " + c.getName() + "=" + c.getValue() + "\n").getBytes());
                }
                response.write(true, ByteBuffer.wrap(buff.toByteArray()), callback);
                return true;
            }
        });
        
        server.start();
        String sessionId1 = "JSESSIONID=node0o250bm47otmz1qjqqor54fj6h0.node0";
        String sessionId2 = "JSESSIONID=node0q4z00xb0pnyl1f312ec6e93lw1.node0";
        String sessionId3 = "JSESSIONID=node0gqgmw5fbijm0f9cid04b4ssw2.node0";
        String request1 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId1 + "\r\n\r\n";
        String request2 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId2 + "\r\n\r\n";
        String request3 = "GET /ctx HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId3 + "\r\n\r\n";
        
        try (LocalEndPoint lep = connector.connect())
        {
            lep.addInput(request1);
            HttpTester.Response response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId1, new String[]{sessionId2, sessionId3}, response.getContent());
            lep.addInput(request2);
            response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId2, new String[]{sessionId1, sessionId3}, response.getContent());
            lep.addInput(request3);
            response = HttpTester.parseResponse(lep.getResponse());
            checkCookieResult(sessionId3, new String[]{sessionId1, sessionId2}, response.getContent());
        }
    }

    /**
     * Test for GET behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testGETNoConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
            GET / HTTP/1.1
            Host: tester
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * Test for HEAD behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testHEADNoConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester

                """;

        LocalConnector.LocalEndPoint localEndPoint = connector.executeRequest(rawRequest);
        ByteBuffer rawResponse = localEndPoint.waitForResponse(true, 2, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseHeadResponse(rawResponse);
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * Test for HEAD behavior on persistent connection (not Connection: close)
     *
     * @throws Exception if there is a problem
     */
    @Test
    public void testHEADWithConnectionClose() throws Exception
    {
        server.stop();
        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                byte[] buf = new byte[4096];
                Arrays.fill(buf, (byte)'x');
                response.write(true, ByteBuffer.wrap(buf), callback);
                return true;
            }
        });

        server.start();

        String rawRequest = """
                HEAD / HTTP/1.1
                Host: tester
                Connection: close
                            
                """;

        HttpTester.Response response = HttpTester.parseHeadResponse(connector.getResponse(rawRequest));
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    public static Stream<Arguments> localeTests()
    {
        return Stream.of(
            Arguments.of(null, List.of(Locale.getDefault().toLanguageTag()).toString()),
            Arguments.of("zz", "[zz]"),
            Arguments.of("en", "[en]"),
            Arguments.of("en-gb", List.of(Locale.UK.toLanguageTag()).toString()),
            Arguments.of("en-us", List.of(Locale.US.toLanguageTag()).toString()),
            Arguments.of("EN-US", List.of(Locale.US.toLanguageTag()).toString()),
            Arguments.of("en-us,en-gb", List.of(Locale.US.toLanguageTag(), Locale.UK.toLanguageTag()).toString()),
            Arguments.of("en-us;q=0.5,fr;q=0.0,en-gb;q=1.0", List.of(Locale.UK.toLanguageTag(), Locale.US.toLanguageTag()).toString()),
            Arguments.of("en-us;q=0.5,zz-yy;q=0.7,en-gb;q=1.0", List.of(Locale.UK.toLanguageTag(), Locale.US.toLanguageTag(), "zz-YY").toString())
        );
    }

    @ParameterizedTest
    @MethodSource("localeTests")
    public void testAcceptableLocales(String acceptLanguage, String expectedLocales) throws Exception
    {
        acceptLanguage = acceptLanguage == null ? "" : (HttpHeader.ACCEPT_LANGUAGE.asString() + ": " + acceptLanguage + "\n");
        String rawRequest = """
                GET / HTTP/1.1
                Host: tester
                Connection: close
                %s
                """.formatted(acceptLanguage);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertNotNull(response);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("locales=" + expectedLocales));
    }

    private static void checkCookieResult(String containedCookie, String[] notContainedCookies, String response)
    {
        assertNotNull(containedCookie);
        assertNotNull(response);
        assertThat(response, containsString("Core Cookie: " + containedCookie));
        if (notContainedCookies != null)
        {
            for (String notContainsCookie : notContainedCookies)
            {
                assertThat(response, not(containsString(notContainsCookie)));
            }
        }
    }

    /**
     * Test to ensure that response.write() will add Content-Length on HTTP/1.1 responses.
     */
    @ParameterizedTest
    @ValueSource(strings = { "true", "false"})
    public void testBadUtf8Query(boolean allowBadUtf8) throws Exception
    {
        server.stop();
        List<ComplianceViolation.Event> events = new CopyOnWriteArrayList<>();
        ComplianceViolation.Listener listener = new ComplianceViolation.Listener()
        {
            @Override
            public void onComplianceViolation(ComplianceViolation.Event event)
            {
                events.add(event);
            }
        };

        if (allowBadUtf8)
        {
            for (Connector connector : server.getConnectors())
            {
                HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
                if (httpConnectionFactory != null)
                {
                    HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                    httpConfiguration.setUriCompliance(UriCompliance.DEFAULT.with("test", UriCompliance.Violation.BAD_UTF8_ENCODING));
                    httpConfiguration.addComplianceViolationListener(listener);
                }
            }
        }

        server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (allowBadUtf8)
                {
                    Fields fields = Request.extractQueryParameters(request);
                    assertThat(fields.getValue("param"), is("bad_�"));
                    assertThat(fields.getValue("other"), is("short�"));
                }
                else
                {
                    assertThrows(IllegalArgumentException.class, () -> Request.extractQueryParameters(request));
                }
                callback.succeeded();
                return true;
            }
        });
        server.start();

        String request = """
            GET /foo?param=bad_%e0%b8&other=short%a HTTP/1.1\r
            Host: local\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        if (allowBadUtf8)
        {
            assertThat(events.size(), is(1));
            assertThat(events.get(0).violation(), is(UriCompliance.Violation.BAD_UTF8_ENCODING));
        }
        else
        {
            assertThat(events.size(), is(0));
        }
    }
}
