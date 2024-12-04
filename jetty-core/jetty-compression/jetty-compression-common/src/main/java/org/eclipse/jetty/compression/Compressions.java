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

package org.eclipse.jetty.compression;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Compression} implementations available via {@link java.util.ServiceLoader}.
 */
public class Compressions
{
    private static final Logger LOG = LoggerFactory.getLogger(Compressions.class);
    private static final AutoLock LOCK = new AutoLock();
    private static final Map<String, Compression> COMPRESSION_MAP = new HashMap<>();

    public static Collection<Compression> getKnown()
    {
        try (AutoLock ignored = LOCK.lock())
        {
            if (COMPRESSION_MAP.isEmpty())
            {
                TypeUtil.serviceProviderStream(ServiceLoader.load(Compression.class)).forEach(
                    compressionProvider ->
                    {
                        try
                        {
                            Compression compression = compressionProvider.get();
                            COMPRESSION_MAP.put(compression.getEncodingName(), compression);
                        }
                        catch (Throwable e)
                        {
                            LOG.warn("Unable to get Compression", e);
                        }
                    }
                );
            }
            return COMPRESSION_MAP.values();
        }
    }

    public static Compression get(String encodingName)
    {
        return COMPRESSION_MAP.get(encodingName);
    }
}
