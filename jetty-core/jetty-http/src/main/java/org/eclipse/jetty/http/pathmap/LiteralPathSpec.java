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

package org.eclipse.jetty.http.pathmap;

import org.eclipse.jetty.util.StringUtil;

public class LiteralPathSpec extends AbstractPathSpec
{
    private final String _pathSpec;
    private final int _pathDepth;

    public LiteralPathSpec(String pathSpec)
    {
        if (StringUtil.isEmpty(pathSpec))
            throw new IllegalArgumentException();
        _pathSpec = pathSpec;

        int pathDepth = 0;
        for (int i = 0; i < _pathSpec.length(); i++)
        {
            char c = _pathSpec.charAt(i);
            if (c < 128)
            {
                if (c == '/')
                    pathDepth++;
            }
        }
        _pathDepth = pathDepth;
    }

    @Override
    public int getSpecLength()
    {
        return _pathSpec.length();
    }

    @Override
    public PathSpecGroup getGroup()
    {
        return PathSpecGroup.EXACT;
    }

    @Override
    public int getPathDepth()
    {
        return _pathDepth;
    }

    @Override
    public String getPathInfo(String path)
    {
        return _pathSpec.equals(path) ? "" : null;
    }

    @Override
    public String getPathMatch(String path)
    {
        return _pathSpec.equals(path) ? _pathSpec : null;
    }

    @Override
    public String getDeclaration()
    {
        return _pathSpec;
    }

    @Override
    public String getPrefix()
    {
        return null;
    }

    @Override
    public String getSuffix()
    {
        return null;
    }

    @Override
    public MatchedPath matched(String path)
    {
        if (_pathSpec.equals(path))
            return MatchedPath.from(_pathSpec, null);
        return null;
    }

    @Override
    public boolean matches(String path)
    {
        return _pathSpec.equals(path);
    }
}
