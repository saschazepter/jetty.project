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

package org.eclipse.jetty.compression.zstandard.internal;

import java.nio.ByteBuffer;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class ZstandardDecoderSource extends DecoderSource
{
    private final ZstandardCompression compression;
    private final ZstdDecompressCtx decompressCtx;

    public ZstandardDecoderSource(Content.Source source, ZstandardCompression compression, ZstandardDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
        this.decompressCtx.setMagicless(config.isMagicless());
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        ByteBuffer input = inputChunk.getByteBuffer();
        if (!inputChunk.hasRemaining())
            return inputChunk;
        if (!input.isDirect())
            throw new IllegalArgumentException("Read Chunk is not a Direct ByteBuffer");
        RetainableByteBuffer dst = compression.acquireByteBuffer();
        boolean last = inputChunk.isLast();
        dst.getByteBuffer().clear();
        boolean fullyFlushed = decompressCtx.decompressDirectByteBufferStream(dst.getByteBuffer(), input);
        if (!fullyFlushed)
            last = false;
        dst.getByteBuffer().flip();
        return Content.Chunk.asChunk(dst.getByteBuffer(), last, dst);
    }
}
