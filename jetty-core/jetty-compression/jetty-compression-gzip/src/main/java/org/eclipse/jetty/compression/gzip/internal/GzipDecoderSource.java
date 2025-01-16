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

package org.eclipse.jetty.compression.gzip.internal;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.gzip.GzipDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipDecoderSource extends DecoderSource
{
    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE, FINISHED, ERROR
    }

    // Unsigned Integer Max == 2^32
    private static final long UINT_MAX = 0xFFFFFFFFL;
    private final GzipCompression compression;
    private final int bufferSize;
    private final InflaterPool.Entry inflaterEntry;
    private final Inflater inflater;
    private State state;
    private int size;
    private long value;
    private byte flags;

    public GzipDecoderSource(Content.Source source, GzipCompression compression, GzipDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        this.inflaterEntry = compression.getInflaterPool().acquire();
        this.inflater = inflaterEntry.get();
        this.inflater.reset();
        this.bufferSize = config.getBufferSize();
        this.state = State.INITIAL;
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        ByteBuffer compressed = inputChunk.getByteBuffer();
        try
        {
            while (true)
            {
                switch (state)
                {
                    case INITIAL ->
                    {
                        inflater.reset();
                        state = State.ID;
                    }
                    case FLAGS ->
                    {
                        if ((flags & 0x04) == 0x04)
                        {
                            state = State.EXTRA_LENGTH;
                            size = 0;
                            value = 0;
                        }
                        else if ((flags & 0x08) == 0x08)
                        {
                            state = State.NAME;
                        }
                        else if ((flags & 0x10) == 0x10)
                        {
                            state = State.COMMENT;
                        }
                        else if ((flags & 0x2) == 0x2)
                        {
                            state = State.HCRC;
                            size = 0;
                            value = 0;
                        }
                        else
                        {
                            state = State.DATA;
                            continue;
                        }
                    }
                    case DATA ->
                    {
                        while (true)
                        {
                            RetainableByteBuffer buffer = compression.acquireByteBuffer(bufferSize);
                            try
                            {
                                ByteBuffer decoded = buffer.getByteBuffer();
                                int pos = BufferUtil.flipToFill(decoded);
                                inflater.inflate(decoded);
                                BufferUtil.flipToFlush(decoded, pos);
                                if (buffer.hasRemaining())
                                    return Content.Chunk.asChunk(decoded, false, buffer);
                                buffer.release();
                            }
                            catch (DataFormatException x)
                            {
                                buffer.release();
                                ZipException failure = new ZipException();
                                failure.initCause(x);
                                throw failure;
                            }

                            if (inflater.needsInput())
                            {
                                if (!compressed.hasRemaining())
                                    return Content.Chunk.EMPTY;
                                inflater.setInput(compressed);
                                // Loop around and try again to inflate.
                            }
                            else if (inflater.finished())
                            {
                                state = State.CRC;
                                size = 0;
                                value = 0;
                                break;
                            }
                        }
                    }
                }

                if (inputChunk.isEmpty())
                    return inputChunk.isLast() ? Content.Chunk.EOF : Content.Chunk.EMPTY;

                byte currByte = compressed.get();
                switch (state)
                {
                    case ERROR, FINISHED ->
                    {
                        // skip rest of content (nothing else possible to read safely)
                        compressed.position(compressed.limit());
                        return Content.Chunk.EOF;
                    }
                    case ID ->
                    {
                        value += (long)(currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 2)
                        {
                            if (value != 0x8B1F)
                                throw new ZipException("Invalid gzip bytes");
                            state = State.CM;
                        }
                    }
                    case CM ->
                    {
                        if ((currByte & 0xFF) != 0x08)
                            throw new ZipException("Invalid gzip compression method");
                        state = State.FLG;
                    }
                    case FLG ->
                    {
                        flags = currByte;
                        state = State.MTIME;
                        size = 0;
                        value = 0;
                    }
                    case MTIME ->
                    {
                        // Skip the 4 MTIME bytes
                        ++size;
                        if (size == 4)
                            state = State.XFL;
                    }
                    case XFL -> state = State.OS; // Skip XFL
                    case OS -> state = State.FLAGS; // Skip OS
                    case EXTRA_LENGTH ->
                    {
                        value += (long)(currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 2)
                            state = State.EXTRA;
                    }
                    case EXTRA ->
                    {
                        // Skip EXTRA bytes
                        --value;
                        if (value == 0)
                        {
                            // Clear the EXTRA flag and loop on the flags
                            flags &= ~0x04;
                            state = State.FLAGS;
                        }
                    }
                    case NAME ->
                    {
                        // Skip NAME bytes
                        if (currByte == 0)
                        {
                            // Clear the NAME flag and loop on the flags
                            flags &= ~0x08;
                            state = State.FLAGS;
                        }
                    }
                    case COMMENT ->
                    {
                        // Skip COMMENT bytes
                        if (currByte == 0)
                        {
                            // Clear the COMMENT flag and loop on the flags
                            flags &= ~0x10;
                            state = State.FLAGS;
                        }
                    }
                    case HCRC ->
                    {
                        // Skip HCRC
                        ++size;
                        if (size == 2)
                        {
                            // Clear the HCRC flag and loop on the flags
                            flags &= ~0x02;
                            state = State.FLAGS;
                        }
                    }
                    case CRC ->
                    {
                        value += (long)(currByte & 0xFF) << 8 * size;
                        ++size;
                        if (size == 4)
                        {
                            // From RFC 1952, compliant decoders need not verify the CRC
                            state = State.ISIZE;
                            size = 0;
                            value = 0;
                        }
                    }
                    case ISIZE ->
                    {
                        value = value | ((currByte & 0xFFL) << (8 * size));
                        ++size;
                        if (size == 4)
                        {
                            // RFC 1952: Section 2.3.1; ISIZE is the input size modulo 2^32
                            if (value != (inflater.getBytesWritten() & UINT_MAX))
                                throw new ZipException("Invalid input size");
                            state = State.INITIAL;
                            size = 0;
                            value = 0;
                            return Content.Chunk.EOF;
                        }
                    }
                    default -> throw new ZipException("Unknown state: " + state);
                }
            }
        }
        catch (ZipException x)
        {
            state = State.ERROR;
            return Content.Chunk.from(x, true);
        }
    }

    @Override
    protected void release()
    {
        inflaterEntry.release();
    }
}