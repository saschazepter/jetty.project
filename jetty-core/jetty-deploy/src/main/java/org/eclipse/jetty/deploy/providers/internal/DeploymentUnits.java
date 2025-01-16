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

package org.eclipse.jetty.deploy.providers.internal;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.providers.Unit;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The role of DeploymentUnits is to keep track of {@link Unit} instances, and
 * process changes coming from {@link java.util.Scanner} into a set of units
 * that will be processed via a listener.
 */
public class DeploymentUnits implements Scanner.ChangeSetListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentUnits.class);

    /**
     * Basename of unit, to the Unit instance.
     */
    private Map<String, Unit> units = new HashMap<>();

    private Listener listener;

    public Listener getListener()
    {
        return listener;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    public Unit getUnit(String basename)
    {
        return units.get(basename);
    }

    public Collection<Unit> getUnits()
    {
        return units.values();
    }

    @Override
    public void pathsChanged(Map<Path, Scanner.Notification> changeSet)
    {
        Objects.requireNonNull(changeSet);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("processChanges: changeSet: {}",
                changeSet.entrySet()
                    .stream()
                    .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(", ", "[", "]"))
            );
        }

        Set<String> changedBaseNames = new HashSet<>();

        for (Map.Entry<Path, Scanner.Notification> entry : changeSet.entrySet())
        {
            Path path = entry.getKey();
            Unit.State state = toState(entry.getValue());

            // to lower-case uses system Locale, as we are working with the system FS.
            String basename = FileID.getBasename(path).toLowerCase();
            changedBaseNames.add(basename);

            Unit unit = units.computeIfAbsent(basename, Unit::new);
            unit.putPath(path, state);
        }

        Listener listener = getListener();
        if (listener != null)
        {
            List<Unit> changedUnits = changedBaseNames.stream()
                .map(name -> units.get(name))
                .collect(Collectors.toList());

            listener.unitsChanged(changedUnits);
        }
    }

    private Unit.State toState(Scanner.Notification notification)
    {
        return switch (notification)
        {
            case ADDED ->
            {
                yield Unit.State.ADDED;
            }
            case CHANGED ->
            {
                yield Unit.State.CHANGED;
            }
            case REMOVED ->
            {
                yield Unit.State.REMOVED;
            }
        };
    }

    public interface Listener extends EventListener
    {
        void unitsChanged(List<Unit> units);
    }
}
