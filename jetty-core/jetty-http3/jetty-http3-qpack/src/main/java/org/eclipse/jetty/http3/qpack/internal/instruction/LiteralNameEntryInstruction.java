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

package org.eclipse.jetty.http3.qpack.internal.instruction;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.TypeUtil;

public class LiteralNameEntryInstruction implements Instruction
{
    private final boolean _huffman;
    private final String _name;
    private final String _value;

    public LiteralNameEntryInstruction(HttpField httpField, boolean huffman)
    {
        _huffman = huffman;
        _name = httpField.getLowerCaseName();
        _value = httpField.getValue();
    }

    @Override
    public void encode(RetainableByteBuffer.Mutable accumulator)
    {
        accumulator.put((byte)0x40); // Instruction Pattern.
        NBitStringEncoder.encode(accumulator, 6, _name, _huffman);
        NBitStringEncoder.encode(accumulator, 8, _value, _huffman);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[name=%s,value=%s]", TypeUtil.toShortName(getClass()), hashCode(), _name, _value);
    }
}
