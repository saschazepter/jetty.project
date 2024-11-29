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

package org.eclipse.jetty.client.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.util.thread.Invocable;

public class HttpClientConnectionFactory implements ClientConnectionFactory, Invocable
{
    /**
     * <p>Representation of the {@code HTTP/1.1} application protocol used by {@link HttpClientTransportDynamic}.</p>
     */
    public static final Info HTTP11 = new HTTP11();

    private boolean initializeConnections;
    private InvocationType invocationType = InvocationType.BLOCKING;

    /**
     * @return whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    public boolean isInitializeConnections()
    {
        return initializeConnections;
    }

    /**
     * @param initialize whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    public void setInitializeConnections(boolean initialize)
    {
        this.initializeConnections = initialize;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return invocationType;
    }

    public void setInvocationType(InvocationType invocationType)
    {
        this.invocationType = Objects.requireNonNull(invocationType);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpConnectionOverHTTP connection = new HttpConnectionOverHTTP(endPoint, context);
        connection.setInitialize(isInitializeConnections());
        connection.setInvocationType(getInvocationType());
        return customize(connection, context);
    }

    /**
     * <p>Representation of the {@code HTTP/1.1} application protocol used by {@link HttpClientTransportDynamic}.</p>
     * <p>Applications should prefer using the constant {@link HttpClientConnectionFactory#HTTP11}, unless they
     * need to customize the associated {@link HttpClientConnectionFactory}.</p>
     */
    public static class HTTP11 extends Info
    {
        private static final List<String> protocols = List.of("http/1.1");

        public HTTP11()
        {
            this(new HttpClientConnectionFactory());
        }

        public HTTP11(ClientConnectionFactory factory)
        {
            super(factory);
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            return protocols;
        }

        @Override
        public Transport newTransport()
        {
            return Transport.TCP_IP;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x%s", getClass().getSimpleName(), hashCode(), protocols);
        }
    }
}
