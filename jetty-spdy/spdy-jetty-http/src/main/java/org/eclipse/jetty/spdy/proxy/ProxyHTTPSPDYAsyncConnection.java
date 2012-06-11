/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.AsyncHttpConnection;
import org.eclipse.jetty.spdy.ISession;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.StandardStream;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;

public class ProxyHTTPSPDYAsyncConnection extends AsyncHttpConnection
{
    private final Headers headers = new Headers();
    private final short version;
    private final ProxyEngine proxyEngine;
    private final HttpGenerator generator;
    private final ISession session;
    private Stream stream;
    private Buffer content;

    public ProxyHTTPSPDYAsyncConnection(SPDYServerConnector connector, EndPoint endpoint, short version, ProxyEngine proxyEngine)
    {
        super(connector, endpoint, connector.getServer());
        this.version = version;
        this.proxyEngine = proxyEngine;
        this.generator = (HttpGenerator)_generator;
        this.session = new HTTPSession(version, connector);
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint)super.getEndPoint();
    }

    @Override
    protected void startRequest(Buffer method, Buffer uri, Buffer httpVersion) throws IOException
    {
        SPDYServerConnector connector = (SPDYServerConnector)getConnector();
        String scheme = connector.getSslContextFactory() != null ? "https" : "http";
        headers.put(HTTPSPDYHeader.SCHEME.name(version), scheme);
        headers.put(HTTPSPDYHeader.METHOD.name(version), method.toString("UTF-8"));
        headers.put(HTTPSPDYHeader.URI.name(version), uri.toString("UTF-8"));
        headers.put(HTTPSPDYHeader.VERSION.name(version), httpVersion.toString("UTF-8"));
    }

    @Override
    protected void parsedHeader(Buffer name, Buffer value) throws IOException
    {
        String headerName = name.toString("UTF-8").toLowerCase();
        String headerValue = value.toString("UTF-8");
        switch (headerName)
        {
            case "host":
                headers.put(HTTPSPDYHeader.HOST.name(version), headerValue);
                break;
            default:
                headers.put(headerName, headerValue);
                break;
        }
    }

    @Override
    protected void headerComplete() throws IOException
    {
    }

    @Override
    protected void content(Buffer buffer) throws IOException
    {
        if (content == null)
        {
            stream = syn(false);
            content = buffer;
        }
        else
        {
            proxyEngine.onData(stream, toDataInfo(buffer, false));
        }
    }

    @Override
    public void messageComplete(long contentLength) throws IOException
    {
        if (stream == null)
        {
            assert content == null;
            if (headers.isEmpty())
                proxyEngine.onGoAway(session, new GoAwayInfo(0, SessionStatus.OK));
            else
                syn(true);
        }
        else
        {
            proxyEngine.onData(stream, toDataInfo(content, true));
        }
        headers.clear();
        stream = null;
        content = null;
    }

    private Stream syn(boolean close)
    {
        // TODO: stream id uniqueness
        Stream stream = new HTTPStream(1, (byte)0, session);
        proxyEngine.onSyn(stream, new SynInfo(headers, close));
        return stream;
    }

    private DataInfo toDataInfo(Buffer buffer, boolean close)
    {
        if (buffer instanceof ByteArrayBuffer)
            return new BytesDataInfo(buffer.array(), buffer.getIndex(), buffer.length(), close);

        if (buffer instanceof NIOBuffer)
        {
            ByteBuffer byteBuffer = ((NIOBuffer)buffer).getByteBuffer();
            byteBuffer.limit(buffer.putIndex());
            byteBuffer.position(buffer.getIndex());
            return new ByteBufferDataInfo(byteBuffer, close);
        }

        return new BytesDataInfo(buffer.asArray(), close);
    }

    private class HTTPSession extends StandardSession
    {
        private HTTPSession(short version, SPDYServerConnector connector)
        {
            super(version, connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), null, null, 1, proxyEngine, null, null);
        }

        @Override
        public void goAway(long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                getEndPoint().close();
                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }
    }

    /**
     * <p>This stream will convert the SPDY invocations performed by the proxy into HTTP to be sent to the client.</p>
     */
    private class HTTPStream extends StandardStream
    {
        private final Pattern statusRegexp = Pattern.compile("(\\d{3})\\s*(.*)");

        private HTTPStream(int id, byte priority, ISession session)
        {
            super(id, priority, session, null);
        }

        @Override
        public void syn(SynInfo synInfo, long timeout, TimeUnit unit, Handler<Stream> handler)
        {
            // No support for pushed stream in HTTP, but we need to return a non-null stream anyway
            // TODO
            throw new UnsupportedOperationException();
        }

        @Override
        public void headers(HeadersInfo headersInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            // TODO
            throw new UnsupportedOperationException();
        }

        @Override
        public void reply(ReplyInfo replyInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                Headers headers = new Headers(replyInfo.getHeaders(), false);

                headers.remove(HTTPSPDYHeader.SCHEME.name(version));

                String status = headers.remove(HTTPSPDYHeader.STATUS.name(version)).value();
                Matcher matcher = statusRegexp.matcher(status);
                matcher.matches();
                int code = Integer.parseInt(matcher.group(1));
                String reason = matcher.group(2);
                generator.setResponse(code, reason);

                String httpVersion = headers.remove(HTTPSPDYHeader.VERSION.name(version)).value();
                generator.setVersion(Integer.parseInt(httpVersion.replaceAll("\\D", "")));

                Headers.Header host = headers.remove(HTTPSPDYHeader.HOST.name(version));
                if (host != null)
                    headers.put("host", host.value());

                HttpFields fields = new HttpFields();
                for (Headers.Header header : headers)
                {
                    String name = camelize(header.name());
                    fields.put(name, header.value());
                }
                generator.completeHeader(fields, replyInfo.isClose());

                if (replyInfo.isClose())
                    complete();

                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }

        private String camelize(String name)
        {
            char[] chars = name.toCharArray();
            chars[0] = Character.toUpperCase(chars[0]);

            for (int i = 0; i < chars.length; ++i)
            {
                char c = chars[i];
                int j = i + 1;
                if (c == '-' && j < chars.length)
                    chars[j] = Character.toUpperCase(chars[j]);
            }
            return new String(chars);
        }

        @Override
        public void data(DataInfo dataInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                // Data buffer must be copied, as the ByteBuffer is pooled
                ByteBuffer byteBuffer = dataInfo.asByteBuffer(false);

                Buffer buffer = byteBuffer.isDirect() ?
                        new DirectNIOBuffer(byteBuffer, false) :
                        new IndirectNIOBuffer(byteBuffer, false);

                generator.addContent(buffer, dataInfo.isClose());
                generator.flush(unit.toMillis(timeout));

                if (dataInfo.isClose())
                    complete();

                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }

        private void complete() throws IOException
        {
            generator.complete();
            // We need to call asyncDispatch() as if the HTTP request
            // has been suspended and now we complete the response
            getEndPoint().asyncDispatch();
        }
    }
}
