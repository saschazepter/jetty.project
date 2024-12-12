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

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class AbstractTest
{
    public WorkDir workDir;

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected Path pemDir;
    protected SslContextFactory.Server sslContextFactoryServer;
    protected ServerQuicConfiguration serverQuicConfig;
    protected Server server;
    protected AbstractConnector connector;
    protected ServletContextHandler servletContextHandler;
    protected HttpClient client;

    public static Collection<TransportType> transports()
    {
        EnumSet<TransportType> transportTypes = EnumSet.allOf(TransportType.class);
        if ("ci".equals(System.getProperty("env")))
            transportTypes.remove(TransportType.H3);
        return transportTypes;
    }

    public static Collection<TransportType> transportsNoFCGI()
    {
        Collection<TransportType> transportTypes = transports();
        transportTypes.remove(TransportType.FCGI);
        return transportTypes;
    }

    public static Collection<TransportType> transportsWithPushSupport()
    {
        Collection<TransportType> transportTypes = transports();
        transportTypes.retainAll(List.of(TransportType.H2C, TransportType.H2));
        return transportTypes;
    }

    public static Collection<TransportType> transportsSecure()
    {
        EnumSet<TransportType> transportTypes = EnumSet.of(TransportType.HTTPS, TransportType.H2, TransportType.H3);
        if ("ci".equals(System.getProperty("env")))
            transportTypes.remove(TransportType.H3);
        return transportTypes;
    }

    @BeforeEach
    public void prepare()
    {
        pemDir = workDir.getEmptyPathDir();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    protected void start(TransportType transportType, HttpServlet servlet) throws Exception
    {
        startServer(transportType, servlet);
        startClient(transportType);
    }

    protected void startServer(TransportType transportType, HttpServlet servlet) throws Exception
    {
        prepareServer(transportType, servlet);
        server.start();
    }

    protected void prepareServer(TransportType transportType, HttpServlet servlet) throws Exception
    {
        sslContextFactoryServer = newSslContextFactoryServer();
        Path serverPemDirectory = Files.createDirectories(pemDir.resolve("server"));
        serverQuicConfig = new ServerQuicConfiguration(sslContextFactoryServer, serverPemDirectory);
        if (server == null)
            server = newServer();
        connector = newConnector(transportType, server);
        server.addConnector(connector);
        servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        servletContextHandler.addServlet(holder, "/*");
        server.setHandler(servletContextHandler);
    }

    protected Server newServer()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        return new Server(serverThreads);
    }

    protected SslContextFactory.Server newSslContextFactoryServer() throws Exception
    {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        configureSslContextFactory(ssl);
        return ssl;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory) throws Exception
    {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Path.of("src/test/resources/keystore.p12")))
        {
            keystore.load(is, "storepwd".toCharArray());
        }
        sslContextFactory.setTrustStore(keystore);
        sslContextFactory.setKeyStore(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    }

    protected void startClient(TransportType transportType) throws Exception
    {
        startClient(transportType, null);
    }

    protected void startClient(TransportType transportType, Consumer<HttpClient> consumer) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(newHttpClientTransport(transportType));
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        if (consumer != null)
            consumer.accept(client);
        client.start();
    }

    public AbstractConnector newConnector(TransportType transportType, Server server)
    {
        return switch (transportType)
        {
            case HTTP, HTTPS, H2C, H2, FCGI ->
                new ServerConnector(server, 1, 1, newServerConnectionFactory(transportType));
            case H3 ->
                new QuicServerConnector(server, serverQuicConfig, newServerConnectionFactory(transportType));
        };
    }

    protected ConnectionFactory[] newServerConnectionFactory(TransportType transportType)
    {
        List<ConnectionFactory> list = switch (transportType)
        {
            case HTTP ->
                List.of(new HttpConnectionFactory(httpConfig));
            case HTTPS ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactoryServer, http.getProtocol());
                yield List.of(ssl, http);
            }
            case H2C ->
            {
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                yield List.of(new HTTP2CServerConnectionFactory(httpConfig));
            }
            case H2 ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactoryServer, alpn.getProtocol());
                yield List.of(ssl, alpn, h2);
            }
            case H3 ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                yield List.of(new HTTP3ServerConnectionFactory(serverQuicConfig, httpConfig));
            }
            case FCGI -> List.of(new ServerFCGIConnectionFactory(httpConfig));
        };
        return list.toArray(ConnectionFactory[]::new);
    }

    protected SslContextFactory.Client newSslContextFactoryClient() throws Exception
    {
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        configureSslContextFactory(ssl);
        ssl.setEndpointIdentificationAlgorithm(null);
        return ssl;
    }

    protected HttpClientTransport newHttpClientTransport(TransportType transportType) throws Exception
    {
        return switch (transportType)
        {
            case HTTP, HTTPS ->
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                yield new HttpClientTransportOverHTTP(clientConnector);
            }
            case H2C, H2 ->
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(newSslContextFactoryClient());
                HTTP2Client http2Client = new HTTP2Client(clientConnector);
                yield new HttpClientTransportOverHTTP2(http2Client);
            }
            case H3 ->
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                SslContextFactory.Client sslContextFactory = newSslContextFactoryClient();
                clientConnector.setSslContextFactory(sslContextFactory);
                Path clientPemDirectory = Files.createDirectories(pemDir.resolve("client"));
                HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, clientPemDirectory));
                yield new HttpClientTransportOverHTTP3(http3Client);
            }
            case FCGI -> new HttpClientTransportOverFCGI(1, "");
        };
    }

    protected URI newURI(TransportType transportType)
    {
        String scheme = transportType.isSecure() ? "https" : "http";
        String uri = scheme + "://localhost";
        if (connector instanceof NetworkConnector networkConnector)
            uri += ":" + networkConnector.getLocalPort();
        return URI.create(uri);
    }

    protected void setStreamIdleTimeout(long idleTimeout)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
        {
            h2.setStreamIdleTimeout(idleTimeout);
        }
        else
        {
            AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
            if (h3 != null)
                h3.getHTTP3Configuration().setStreamIdleTimeout(idleTimeout);
            else
                connector.setIdleTimeout(idleTimeout);
        }
    }

    public enum TransportType
    {
        HTTP, HTTPS, H2C, H2, H3, FCGI;

        public boolean isSecure()
        {
            return switch (this)
            {
                case HTTP, H2C, FCGI -> false;
                case HTTPS, H2, H3 -> true;
            };
        }

        public boolean isMultiplexed()
        {
            return switch (this)
            {
                case HTTP, HTTPS, FCGI -> false;
                case H2C, H2, H3 -> true;
            };
        }
    }
}
