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

package org.eclipse.jetty.deploy.providers;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.App;

/**
 * A Unit of deployment, a basename and all the associated
 * paths that belong to that basename, along with state.
 */
public class Unit
{
    public enum State
    {
        UNCHANGED,
        ADDED,
        CHANGED,
        REMOVED
    }

    private final String baseName;
    private final Map<Path, State> paths = new HashMap<>();
    private App app;

    public Unit(String basename)
    {
        this.baseName = basename;
    }

    public String getBaseName()
    {
        return baseName;
    }

    public State getState()
    {
        // Calculate state of unit from Path states.
        Unit.State state = State.UNCHANGED;
        for (Unit.State pathState : paths.values())
        {
            switch (pathState)
            {
                case ADDED ->
                {
                    if (state == State.UNCHANGED || state == State.REMOVED)
                        state = State.ADDED;
                }
                case CHANGED ->
                {
                    state = State.CHANGED;
                }
                case REMOVED ->
                {
                    if (state == State.UNCHANGED)
                        state = State.REMOVED;
                }
            }
        }
        return state;
    }

    public Map<Path, State> getPaths()
    {
        return paths;
    }

    public void putPath(Path path, State state)
    {
        this.paths.put(path, state);
    }

    public App getApp()
    {
        return app;
    }

    public void setApp(App app)
    {
        this.app = app;
    }

    public App removeApp()
    {
        App oldApp = this.app;
        this.app = null;
        return oldApp;
    }

    public void setUnchanged()
    {
        paths.replaceAll((p, v) -> State.UNCHANGED);
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Unit[").append(baseName);
        str.append("|").append(getState());
        str.append(", paths=");
        str.append(paths.entrySet().stream()
            .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
            .collect(Collectors.joining(", ", "[", "]"))
        );
        str.append(", app=");
        if (app == null)
            str.append("<null>");
        else
            str.append(app);
        str.append("]");
        return str.toString();
    }
}
