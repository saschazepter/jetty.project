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

package org.eclipse.jetty.compression.gzip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.util.List;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.EncoderConfig;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.gzip.internal.ConfigurableGzipInputStream;
import org.eclipse.jetty.compression.gzip.internal.ConfigurableGzipOutputStream;
import org.eclipse.jetty.compression.gzip.internal.GzipDecoderSource;
import org.eclipse.jetty.compression.gzip.internal.GzipEncoderSink;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipCompression extends Compression
{
    private static final int DEFAULT_MIN_GZIP_SIZE = 32;
    private static final List<String> EXTENSIONS = List.of("gz", "gzip");
    private static final String ENCODING_NAME = "gzip";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);

    private DeflaterPool deflaterPool;
    private InflaterPool inflaterPool;
    private GzipEncoderConfig defaultEncoderConfig = new GzipEncoderConfig();
    private GzipDecoderConfig defaultDecoderConfig = new GzipDecoderConfig();

    public GzipCompression()
    {
        super(ENCODING_NAME);
        setMinCompressSize(DEFAULT_MIN_GZIP_SIZE);
    }

    @Override
    public RetainableByteBuffer acquireByteBuffer()
    {
        return acquireByteBuffer(getBufferSize());
    }

    @Override
    public RetainableByteBuffer acquireByteBuffer(int length)
    {
        // Zero-capacity buffers aren't released, they MUST NOT come from the pool.
        if (length == 0)
            return RetainableByteBuffer.EMPTY;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(length, false);
        buffer.getByteBuffer().order(getByteOrder());
        return buffer;
    }

    @Override
    public HttpField getContentEncodingField()
    {
        return CONTENT_ENCODING;
    }

    @Override
    public DecoderConfig getDefaultDecoderConfig()
    {
        return this.defaultDecoderConfig;
    }

    @Override
    public void setDefaultDecoderConfig(DecoderConfig config)
    {
        this.defaultDecoderConfig = (GzipDecoderConfig)config;
    }

    @Override
    public EncoderConfig getDefaultEncoderConfig()
    {
        return this.defaultEncoderConfig;
    }

    @Override
    public void setDefaultEncoderConfig(EncoderConfig config)
    {
        this.defaultEncoderConfig = (GzipEncoderConfig)config;
    }

    public DeflaterPool getDeflaterPool()
    {
        return this.deflaterPool;
    }

    public void setDeflaterPool(DeflaterPool deflaterPool)
    {
        this.deflaterPool = deflaterPool;
    }

    @Override
    public List<String> getFileExtensionNames()
    {
        return EXTENSIONS;
    }

    public InflaterPool getInflaterPool()
    {
        return this.inflaterPool;
    }

    public void setInflaterPool(InflaterPool inflaterPool)
    {
        this.inflaterPool = inflaterPool;
    }

    @Override
    public void setMinCompressSize(int minCompressSize)
    {
        super.setMinCompressSize(Math.max(minCompressSize, DEFAULT_MIN_GZIP_SIZE));
    }

    @Override
    public String getName()
    {
        return "gzip";
    }

    @Override
    public HttpField getXContentEncodingField()
    {
        return X_CONTENT_ENCODING;
    }

    @Override
    public InputStream newDecoderInputStream(InputStream in, DecoderConfig config) throws IOException
    {
        GzipDecoderConfig gzipDecoderConfig = (GzipDecoderConfig)config;
        return new ConfigurableGzipInputStream(in, gzipDecoderConfig);
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source, DecoderConfig config)
    {
        GzipDecoderConfig gzipDecoderConfig = (GzipDecoderConfig)config;
        return new GzipDecoderSource(source, this, gzipDecoderConfig);
    }

    @Override
    public OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException
    {
        GzipEncoderConfig gzipEncoderConfig = (GzipEncoderConfig)config;
        return new ConfigurableGzipOutputStream(out, gzipEncoderConfig);
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config)
    {
        GzipEncoderConfig gzipEncoderConfig = (GzipEncoderConfig)config;
        return new GzipEncoderSink(this, sink, gzipEncoderConfig);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (deflaterPool == null)
        {
            deflaterPool = DeflaterPool.ensurePool(getContainer());
        }
        addBean(deflaterPool);

        if (inflaterPool == null)
        {
            inflaterPool = InflaterPool.ensurePool(getContainer());
        }
        addBean(inflaterPool);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        removeBean(inflaterPool);
        inflaterPool = null;

        removeBean(deflaterPool);
        deflaterPool = null;
    }

    private ByteOrder getByteOrder()
    {
        // Per RFC-1952, GZIP is LITTLE_ENDIAN
        return ByteOrder.LITTLE_ENDIAN;
    }
}
