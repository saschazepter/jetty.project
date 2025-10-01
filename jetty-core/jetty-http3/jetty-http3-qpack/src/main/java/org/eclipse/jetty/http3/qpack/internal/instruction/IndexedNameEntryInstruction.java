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

import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.http.compression.NBitStringEncoder;
import org.eclipse.jetty.http3.qpack.Instruction;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.TypeUtil;

public record IndexedNameEntryInstruction(boolean dynamic, int index, boolean huffman, String value) implements Instruction
{
    @Override
    public void encode(RetainableByteBuffer.Mutable accumulator)
    {
        // First bit indicates the instruction, second bit is whether it is a dynamic table reference or not.
        accumulator.put((byte)(0x80 | (dynamic ? 0x00 : 0x40)));
        NBitIntegerEncoder.encode(accumulator, 6, index);
        NBitStringEncoder.encode(accumulator, 8, value, huffman);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[index=%d,name=%s]", TypeUtil.toShortName(getClass()), hashCode(), index, value);
    }
}
