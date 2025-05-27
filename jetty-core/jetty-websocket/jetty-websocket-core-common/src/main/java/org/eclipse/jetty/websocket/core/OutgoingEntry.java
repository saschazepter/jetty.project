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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.util.Callback;

// TODO: add methods/fields for frame and message timeouts.
//       needs builder / copy / wrapper so we can change only specific fields.
//       Can this extend the CyclicTimeouts.Expirable directly?
public class OutgoingEntry
{
    private final Frame frame;
    private final Callback callback;
    private final boolean batch;

    public OutgoingEntry(Frame frame, Callback callback, boolean batch)
    {
        this.frame = frame;
        this.callback = callback;
        this.batch = batch;
    }

    public Frame getFrame()
    {
        return frame;
    }

    public Callback getCallback()
    {
        return callback;
    }

    public boolean isBatch()
    {
        return batch;
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}
