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

package org.eclipse.jetty.tests.ccd.common;

import java.util.Map;

public class HttpRequest implements Step
{
    private String method;
    private String requestPath;
    private String body;
    private Map<String, String> headers;

    public static HttpRequest parse(String line)
    {
        String[] parts = line.split("\\|");
        HttpRequest request = new HttpRequest();
        request.setMethod(parts[1]);
        request.setRequestPath(parts[2]);
        if (parts.length > 4)
            request.setBody(parts[3]);
        return request;
    }

    public String getMethod()
    {
        return method;
    }

    public void setMethod(String method)
    {
        this.method = method;
    }

    public String getRequestPath()
    {
        return requestPath;
    }

    public void setRequestPath(String requestPath)
    {
        this.requestPath = requestPath;
    }

    public String getBody()
    {
        return body;
    }

    public void setBody(String body)
    {
        this.body = body;
    }

    public Map<String, String> getHeaders()
    {
        return headers;
    }

    public void setHeaders(Map<String, String> headers)
    {
        this.headers = headers;
    }
}
