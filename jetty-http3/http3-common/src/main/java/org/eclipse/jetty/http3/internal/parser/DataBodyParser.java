//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataBodyParser extends BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(DataBodyParser.class);

    private State state = State.INIT;
    private long length;

    public DataBodyParser(long streamId, HeaderParser headerParser, ParserListener listener)
    {
        super(streamId, headerParser, listener);
    }

    private void reset()
    {
        state = State.INIT;
        length = 0;
    }

    @Override
    protected void emptyBody(ByteBuffer buffer)
    {
        onData(BufferUtil.EMPTY_BUFFER, false);
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case INIT:
                {
                    length = getBodyLength();
                    state = State.DATA;
                    break;
                }
                case DATA:
                {
                    int size = (int)Math.min(buffer.remaining(), length);
                    int position = buffer.position();
                    int limit = buffer.limit();
                    buffer.limit(position + size);
                    ByteBuffer slice = buffer.slice();
                    buffer.limit(limit);
                    buffer.position(position + size);

                    length -= size;
                    if (length == 0)
                    {
                        reset();
                        onData(slice, false);
                        return true;
                    }
                    else
                    {
                        // We got partial data, simulate a smaller frame, and stay in DATA state.
                        onData(slice, true);
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private void onData(ByteBuffer buffer, boolean fragment)
    {
        DataFrame frame = new DataFrame(buffer);
        if (LOG.isDebugEnabled())
            LOG.debug("notifying synthetic={} {}#{}", fragment, frame, getStreamId());
        notifyData(frame);
    }

    private enum State
    {
        INIT, DATA
    }
}