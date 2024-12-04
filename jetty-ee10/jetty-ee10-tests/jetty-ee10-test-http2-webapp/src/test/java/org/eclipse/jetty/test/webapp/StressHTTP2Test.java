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

package org.eclipse.jetty.test.webapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.awaitility.Awaitility.await;

@Tag("stress")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class StressHTTP2Test
{
    private static final int N_THREADS = Runtime.getRuntime().availableProcessors();
    private static final int ITERATIONS = 100;

    private ExecutorService executorService;
    private Server server;

    @Test
    public void testStressHTTP2WithAborts() throws Exception
    {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < N_THREADS; i++)
        {
            Future<Object> f = executorService.submit(() ->
            {
                HTTP2Client http2Client = new HTTP2Client();
                try (HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client)))
                {
                    httpClient.start();

                    for (int j = 0; j < ITERATIONS; j++)
                    {
                        CompletableFuture<Object> cf = new CompletableFuture<>();
                        Request request = httpClient.newRequest(server.getURI());
                        request.path("/")
                            .method(HttpMethod.GET)
                            .send(result ->
                            {
                                if (result.isSucceeded())
                                    cf.complete(null);
                                else
                                    cf.completeExceptionally(result.getFailure());
                            });

                        if (j % (ITERATIONS / 10) == 0)
                            request.abort(new Exception("client abort"));

                        try
                        {
                            cf.get();
                        }
                        catch (Exception e)
                        {
                            // ignore
                        }

                        // if ((j + 1) % 100 == 0)
                        //     System.err.println(Thread.currentThread().getName() + " processed " + (j + 1));
                    }
                }
                return null;
            });
            futures.add(f);
        }

        for (Future<?> future : futures)
        {
            future.get();
        }

        // Assert that no thread is stuck in WAITING state, i.e.: blocked on some lock.
        await().atMost(30, TimeUnit.SECONDS).until(() ->
        {
            TestQueuedThreadPool queuedThreadPool = server.getBean(TestQueuedThreadPool.class);
            for (Thread thread : queuedThreadPool.getCreatedThreads())
            {
                Thread.State state = thread.getState();
                if (state == Thread.State.WAITING)
                    return false;
            }
            return true;
        });
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        startServer();
        executorService = Executors.newFixedThreadPool(N_THREADS);
    }

    @AfterEach
    public void tearDown()
    {
        executorService.shutdownNow();
        LifeCycle.stop(server);
    }

    private void startServer() throws Exception
    {
        QueuedThreadPool qtp = new TestQueuedThreadPool();
        server = new Server(qtp);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setOutputBufferSize(1);
        ConnectionFactory connectionFactory = new HTTP2CServerConnectionFactory(httpConfiguration);
        ServerConnector serverConnector = new ServerConnector(server, connectionFactory);
        serverConnector.setPort(0);
        server.addConnector(serverConnector);

        ServletContextHandler targetContextHandler = new ServletContextHandler();
        targetContextHandler.setContextPath("/");
        targetContextHandler.addServlet(new SyncEE10Servlet(), "/*");

        server.setHandler(targetContextHandler);

        server.start();
    }

    private static class TestQueuedThreadPool extends QueuedThreadPool
    {
        private static final List<Thread> CREATED_THREADS = new CopyOnWriteArrayList<>();
        private static final ThreadFactory THREAD_FACTORY = r ->
        {
            Thread thread = new Thread(r);
            CREATED_THREADS.add(thread);
            return thread;
        };

        public TestQueuedThreadPool()
        {
            super(N_THREADS * 2, 8, 60000, -1, null, null, THREAD_FACTORY);
        }

        public List<Thread> getCreatedThreads()
        {
            return CREATED_THREADS;
        }
    }

    private static class SyncEE10Servlet extends HttpServlet
    {
        private static final byte[] DATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app\s
               xmlns="http://xmlns.jcp.org/xml/ns/javaee"\s
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd"
               metadata-complete="false"
               version="3.1">\s
            
              <!-- ===================================================================== -->
              <!-- This file contains the default descriptor for web applications.       -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -->
              <!-- The intent of this descriptor is to include jetty specific or common  -->
              <!-- configuration for all webapps.   If a context has a webdefault.xml    -->
              <!-- descriptor, it is applied before the context's own web.xml file       -->
              <!--                                                                       -->
              <!-- A context may be assigned a default descriptor by calling             -->
              <!-- WebAppContext.setDefaultsDescriptor(String).                          -->
              <!--                                                                       -->
              <!-- This file is present in the jetty-webapp.jar, and is used as the      -->
              <!-- defaults descriptor if no other is explicitly set on a context.       -->
              <!--                                                                       -->
              <!-- A copy of this file is also placed into the $JETTY_HOME/etc dir of    -->
              <!-- the  distribution, and is referenced by some of the other xml files,  -->
              <!-- eg the jetty-deploy.xml file.                                         -->
              <!-- ===================================================================== -->
            
              <description>
                Default web.xml file. \s
                This file is applied to a Web application before its own WEB_INF/web.xml file
              </description>
            
              <!-- ==================================================================== -->
              <!-- Removes static references to beans from javax.el.BeanELResolver to   -->
              <!-- ensure webapp classloader can be released on undeploy                -->
              <!-- ==================================================================== -->
              <listener>
               <listener-class>org.eclipse.jetty.ee9.servlet.listener.ELContextCleaner</listener-class>
              </listener>
             \s
              <!-- ==================================================================== -->
              <!-- Removes static cache of Methods from java.beans.Introspector to      -->
              <!-- ensure webapp classloader can be released on undeploy                -->
              <!-- ==================================================================== --> \s
              <listener>
               <listener-class>org.eclipse.jetty.ee9.servlet.listener.IntrospectorCleaner</listener-class>
              </listener>
             \s
            
              <!-- ==================================================================== -->
              <!-- Context params to control Session Cookies                            -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <!--
                UNCOMMENT TO ACTIVATE\s
                <context-param>\s
                  <param-name>org.eclipse.jetty.session.SessionDomain</param-name>
                  <param-value>127.0.0.1</param-value>\s
                </context-param>\s
                <context-param>
                  <param-name>org.eclipse.jetty.session.SessionPath</param-name>
                  <param-value>/</param-value>
                </context-param>
                <context-param>
                  <param-name>org.eclipse.jetty.session.MaxAge</param-name>
                  <param-value>-1</param-value>
                </context-param>
              -->
            
              <!-- ==================================================================== -->
              <!-- The default servlet.                                                 -->
              <!-- This servlet, normally mapped to /, provides the handling for static -->
              <!-- content, OPTIONS and TRACE methods for the context.                  -->
              <!-- The following initParameters are supported:                          -->
              <!-- \s
             *  acceptRanges      If true, range requests and responses are
             *                    supported
             *
             *  dirAllowed        If true, directory listings are returned if no
             *                    welcome file is found. Else 403 Forbidden.
             *
             *  welcomeServlets   If true, attempt to dispatch to welcome files
             *                    that are servlets, but only after no matching static
             *                    resources could be found. If false, then a welcome
             *                    file must exist on disk. If "exact", then exact
             *                    servlet matches are supported without an existing file.
             *                    Default is true.
             *
             *                    This must be false if you want directory listings,
             *                    but have index.jsp in your welcome file list.
             *
             *  redirectWelcome   If true, welcome files are redirected rather than
             *                    forwarded to.
             *
             *  gzip              If set to true, then static content will be served as
             *                    gzip content encoded if a matching resource is
             *                    found ending with ".gz"
             *
             *  baseResource      Set to replace the context resource base
             *
             *  resourceCache     If set, this is a context attribute name, which the servlet
             *                    will use to look for a shared ResourceCache instance.
             *
             *  relativeBaseResource
             *                    Set with a pathname relative to the base of the
             *                    servlet context root. Useful for only serving static content out
             *                    of only specific subdirectories.
             *
             *  stylesheet        Set with the location of an optional stylesheet that will be used
             *                    to decorate the directory listing html.
             *
             *  aliases           If True, aliases of resources are allowed (eg. symbolic
             *                    links and caps variations). May bypass security constraints.
             *                   \s
             *  etags             If True, weak etags will be generated and handled.
             *
             *  maxCacheSize      The maximum total size of the cache or 0 for no cache.
             *  maxCachedFileSize The maximum size of a file to cache
             *  maxCachedFiles    The maximum number of files to cache
             *
             *  useFileMappedBuffer
             *                    If set to true, it will use mapped file buffers to serve static content
             *                    when using an NIO connector. Setting this value to false means that
             *                    a direct buffer will be used instead of a mapped file buffer.
             *                    This file sets the value to true.
             *
             *  cacheControl      If set, all static content will have this value set as the cache-control
             *                    header.
             *
             -->
             <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <servlet>
                <servlet-name>default</servlet-name>
                <servlet-class>org.eclipse.jetty.ee9.servlet.DefaultServlet</servlet-class>
                <init-param>
                  <param-name>aliases</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>acceptRanges</param-name>
                  <param-value>true</param-value>
                </init-param>
                <init-param>
                  <param-name>dirAllowed</param-name>
                  <param-value>true</param-value>
                </init-param>
                <init-param>
                  <param-name>welcomeServlets</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>redirectWelcome</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>maxCacheSize</param-name>
                  <param-value>256000000</param-value>
                </init-param>
                <init-param>
                  <param-name>maxCachedFileSize</param-name>
                  <param-value>200000000</param-value>
                </init-param>
                <init-param>
                  <param-name>maxCachedFiles</param-name>
                  <param-value>2048</param-value>
                </init-param>
                <init-param>
                  <param-name>gzip</param-name>
                  <param-value>true</param-value>
                </init-param>
                <init-param>
                  <param-name>etags</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>useFileMappedBuffer</param-name>
                  <param-value>true</param-value>
                </init-param>
                <!--
                <init-param>
                  <param-name>resourceCache</param-name>
                  <param-value>resourceCache</param-value>
                </init-param>
                -->
                <!--
                <init-param>
                  <param-name>cacheControl</param-name>
                  <param-value>max-age=3600,public</param-value>
                </init-param>
                -->
                <load-on-startup>0</load-on-startup>
              </servlet>
            
              <servlet-mapping>
                <servlet-name>default</servlet-name>
                <url-pattern>/</url-pattern>
              </servlet-mapping>
            
            
              <!-- ==================================================================== -->
              <!-- JSP Servlet                                                          -->
              <!-- This is the jasper JSP servlet.                                      -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <!-- The JSP page compiler and execution servlet, which is the mechanism  -->
              <!-- used by the jsp container to support JSP pages.  Traditionally,      -->
              <!-- this servlet is mapped to URL pattern "*.jsp".  This servlet         -->
              <!-- supports the following initialization parameters (default values     -->
              <!-- are in square brackets):                                             -->
              <!--                                                                      -->
              <!--   checkInterval       If development is false and reloading is true, -->
              <!--                       background compiles are enabled. checkInterval -->
              <!--                       is the time in seconds between checks to see   -->
              <!--                       if a JSP page needs to be recompiled. [300]    -->
              <!--                                                                      -->
              <!--   compiler            Which compiler Ant should use to compile JSP   -->
              <!--                       pages.  See the Ant documentation for more     -->
              <!--                       information. [javac]                           -->
              <!--                                                                      -->
              <!--   classdebuginfo      Should the class file be compiled with         -->
              <!--                       debugging information?  [true]                 -->
              <!--                                                                      -->
              <!--   classpath           What class path should I use while compiling   -->
              <!--                       generated servlets?  [Created dynamically      -->
              <!--                       based on the current web application]          -->
              <!--                       Set to ? to make the container explicitly set  -->
              <!--                       this parameter.                                -->
              <!--                                                                      -->
              <!--   development         Is Jasper used in development mode (will check -->
              <!--                       for JSP modification on every access)?  [true] -->
              <!--                                                                      -->
              <!--   enablePooling       Determines whether tag handler pooling is      -->
              <!--                       enabled  [true]                                -->
              <!--                                                                      -->
              <!--   fork                Tell Ant to fork compiles of JSP pages so that -->
              <!--                       a separate JVM is used for JSP page compiles   -->
              <!--                       from the one Tomcat is running in. [true]      -->
              <!--                                                                      -->
              <!--   ieClassId           The class-id value to be sent to Internet      -->
              <!--                       Explorer when using <jsp:plugin> tags.         -->
              <!--                       [clsid:8AD9C840-044E-11D1-B3E9-00805F499D93]   -->
              <!--                                                                      -->
              <!--   javaEncoding        Java file encoding to use for generating java  -->
              <!--                       source files. [UTF-8]                          -->
              <!--                                                                      -->
              <!--   keepgenerated       Should we keep the generated Java source code  -->
              <!--                       for each page instead of deleting it? [true]   -->
              <!--                                                                      -->
              <!--   logVerbosityLevel   The level of detailed messages to be produced  -->
              <!--                       by this servlet.  Increasing levels cause the  -->
              <!--                       generation of more messages.  Valid values are -->
              <!--                       FATAL, ERROR, WARNING, INFORMATION, and DEBUG. -->
              <!--                       [WARNING]                                      -->
              <!--                                                                      -->
              <!--   mappedfile          Should we generate static content with one     -->
              <!--                       print statement per input line, to ease        -->
              <!--                       debugging?  [false]                            -->
              <!--                                                                      -->
              <!--                                                                      -->
              <!--   reloading           Should Jasper check for modified JSPs?  [true] -->
              <!--                                                                      -->
              <!--   suppressSmap        Should the generation of SMAP info for JSR45   -->
              <!--                       debugging be suppressed?  [false]              -->
              <!--                                                                      -->
              <!--   dumpSmap            Should the SMAP info for JSR45 debugging be    -->
              <!--                       dumped to a file? [false]                      -->
              <!--                       False if suppressSmap is true                  -->
              <!--                                                                      -->
              <!--   scratchdir          What scratch directory should we use when      -->
              <!--                       compiling JSP pages?  [default work directory  -->
              <!--                       for the current web application]               -->
              <!--                                                                      -->
              <!--   tagpoolMaxSize      The maximum tag handler pool size  [5]         -->
              <!--                                                                      -->
              <!--   xpoweredBy          Determines whether X-Powered-By response       -->
              <!--                       header is added by generated servlet  [false]  -->
              <!--                                                                      -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <servlet id="jsp">
                <servlet-name>jsp</servlet-name>
                <servlet-class>org.eclipse.jetty.jsp.JettyJspServlet</servlet-class>
                <init-param>
                  <param-name>logVerbosityLevel</param-name>
                  <param-value>DEBUG</param-value>
                </init-param>
                <init-param>
                  <param-name>fork</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>xpoweredBy</param-name>
                  <param-value>false</param-value>
                </init-param>
                <init-param>
                  <param-name>compilerTargetVM</param-name>
                  <param-value>1.7</param-value>
                </init-param>
                <init-param>
                  <param-name>compilerSourceVM</param-name>
                  <param-value>1.7</param-value>
                </init-param>
                <!-- \s
                <init-param>
                    <param-name>classpath</param-name>
                    <param-value>?</param-value>
                </init-param>
                -->
                <load-on-startup>0</load-on-startup>
              </servlet>
            
              <servlet-mapping>
                <servlet-name>jsp</servlet-name>
                <url-pattern>*.jsp</url-pattern>
                <url-pattern>*.jspf</url-pattern>
                <url-pattern>*.jspx</url-pattern>
                <url-pattern>*.xsp</url-pattern>
                <url-pattern>*.JSP</url-pattern>
                <url-pattern>*.JSPF</url-pattern>
                <url-pattern>*.JSPX</url-pattern>
                <url-pattern>*.XSP</url-pattern>
              </servlet-mapping>
            
            
              <!-- ==================================================================== -->
              <!-- Default session configuration                                        -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <session-config>
                <session-timeout>30</session-timeout>
              </session-config>
            
              <!-- ==================================================================== -->
              <!-- Default MIME mappings                                                -->
              <!-- The default MIME mappings are provided by the mime.properties        -->
              <!-- resource in the jetty-http.jar file.  Additional or modified         -->
              <!-- mappings may be specified here                                       -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <!-- UNCOMMENT TO ACTIVATE
              <mime-mapping>
                <extension>mysuffix</extension>
                <mime-type>mymime/type</mime-type>
              </mime-mapping>
              -->
            
              <!-- ==================================================================== -->
              <!-- Default welcome files                                                -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <welcome-file-list>
                <welcome-file>index.html</welcome-file>
                <welcome-file>index.htm</welcome-file>
                <welcome-file>index.jsp</welcome-file>
              </welcome-file-list>
            
              <!-- ==================================================================== -->
              <!-- Default locale encodings                                             -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <locale-encoding-mapping-list>
                <locale-encoding-mapping>
                  <locale>ar</locale>
                  <encoding>ISO-8859-6</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>be</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>bg</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>ca</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>cs</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>da</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>de</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>el</locale>
                  <encoding>ISO-8859-7</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>en</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>es</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>et</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>fi</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>fr</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>hr</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>hu</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>is</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>it</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>iw</locale>
                  <encoding>ISO-8859-8</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>ja</locale>
                  <encoding>Shift_JIS</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>ko</locale>
                  <encoding>EUC-KR</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>lt</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>lv</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>mk</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>nl</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>no</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>pl</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>pt</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>ro</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>ru</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sh</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sk</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sl</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sq</locale>
                  <encoding>ISO-8859-2</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sr</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>sv</locale>
                  <encoding>ISO-8859-1</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>tr</locale>
                  <encoding>ISO-8859-9</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>uk</locale>
                  <encoding>ISO-8859-5</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>zh</locale>
                  <encoding>GB2312</encoding>
                </locale-encoding-mapping>
                <locale-encoding-mapping>
                  <locale>zh_TW</locale>
                  <encoding>Big5</encoding>
                </locale-encoding-mapping>
              </locale-encoding-mapping-list>
            
              <!-- ==================================================================== -->
              <!-- Disable TRACE method with security constraint                        -->
              <!-- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -  -->
              <security-constraint>
                <web-resource-collection>
                  <web-resource-name>Disable TRACE</web-resource-name>
                  <url-pattern>/</url-pattern>
                  <http-method>TRACE</http-method>
                </web-resource-collection>
                <auth-constraint/>
              </security-constraint>
              <security-constraint>
                <web-resource-collection>
                  <web-resource-name>Enable everything but TRACE</web-resource-name>
                  <url-pattern>/</url-pattern>
                  <http-method-omission>TRACE</http-method-omission>
                </web-resource-collection>
              </security-constraint>
            
            </web-app>
            
            """.getBytes(StandardCharsets.UTF_8);

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setStatus(200);
            response.getOutputStream().write(DATA);
        }
    }
}
