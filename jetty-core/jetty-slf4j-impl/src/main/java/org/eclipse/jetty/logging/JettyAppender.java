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

package org.eclipse.jetty.logging;

import org.slf4j.event.Level;

/**
 * The interface to implement to emit/append log events
 */
public interface JettyAppender
{
    /**
     * <p>emit</p>
     * @param logger the {@link JettyLogger} instance to use
     * @param level the logger {@link Level}
     * @param timestamp event timestamp
     * @param threadName the name of the thread to be logged in
     * @param throwable any Exception to log
     * @param message the message
     * @param argumentArray the array of arguments for message replacement
     */
    void emit(JettyLogger logger, Level level, long timestamp, String threadName,
              Throwable throwable, String message, Object... argumentArray);
}
