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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.resource.PathCollators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The role of DeploymentUnits is to keep track groups of files (and directories) that represent
 * a single unit of deployment, identifying additions/changes/deletions of files/dirs properly.
 */
public class DeploymentUnits
{
    enum EventType
    {
        UNCHANGED,
        CHANGED,
        ADDED,
        REMOVED
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentUnits.class);

    /**
     * The units that are being tracked by this component.
     * Key is the basename (case-insensitive) and the value are all the paths known for that unit.
     */
    private HashMap<String, Set<Path>> units = new HashMap<>();

    public Set<Path> getUnit(String basename)
    {
        return units.get(basename);
    }

    public synchronized void processChanges(Set<Path> rawPaths, Events events)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("processChanges: rawPaths: {}", rawPaths.stream()
                .sorted(PathCollators.byName(true))
                .map(Path::toString)
                .collect(Collectors.joining(", ", "[", "]"))
            );
        }
        Map<String, EventType> basenameEvents = new HashMap<>();

        // Figure out what changed, and how.
        for (Path path : rawPaths)
        {
            // to lower-case uses system Locale, as we are working with the system FS.
            String basename = FileID.getBasename(path).toLowerCase();
            basenameEvents.putIfAbsent(basename, EventType.UNCHANGED);

            if (Files.exists(path))
            {
                // A path that exists, either added, or changed.
                if (!units.containsKey(basename))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("basename: {} - new - {}", basename, path);
                    // this is a new unit
                    Set<Path> unitPaths = units.computeIfAbsent(basename, k -> new HashSet<>());
                    unitPaths.add(path);
                    basenameEvents.compute(basename,
                        (b, v) ->
                        {
                            if (v == null)
                                return EventType.ADDED;
                            return switch (v)
                            {
                                case ADDED, CHANGED:
                                {
                                    yield v; // keep value
                                }
                                case UNCHANGED, REMOVED:
                                {
                                    yield EventType.ADDED;
                                }
                            };
                        });
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("basename: {} - existing - {}", basename, path);
                    // this is an existing unit
                    Set<Path> unitPaths = units.computeIfAbsent(basename, k -> new HashSet<>());
                    unitPaths.add(path);
                    units.put(basename, unitPaths);
                    basenameEvents.compute(basename,
                        (b, v) ->
                        {
                            if (v == null)
                                return EventType.CHANGED;
                            return switch (v)
                            {
                                case ADDED, CHANGED ->
                                {
                                    yield v; // keep value
                                }
                                case UNCHANGED, REMOVED ->
                                {
                                    yield EventType.CHANGED;
                                }
                            };
                        });
                }
            }
            else
            {
                // A path was removed

                // Only care about paths that belong to an existing unit
                if (units.containsKey(basename))
                {
                    // this is an existing unit
                    Set<Path> unitPaths = units.get(basename);
                    unitPaths.remove(path);
                    if (unitPaths.isEmpty())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("basename: {} - removed - {}", basename, path);
                        // remove unit
                        basenameEvents.put(basename, EventType.REMOVED);
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("basename: {} - changed - {}", basename, path);
                        // unit has changed.
                        basenameEvents.put(basename, EventType.CHANGED);
                    }
                }
            }
        }

        // Notify of changes, alphabetically.
        List<String> sortedBasenames = basenameEvents.keySet()
            .stream()
            .distinct()
            .sorted()
            .toList();

        for (String basename : sortedBasenames)
        {
            Set<Path> paths = getUnit(basename);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("reporting unit{}: basename: {} - paths: {}",
                    basenameEvents.get(basename),
                    basename,
                    paths.stream()
                        .sorted(PathCollators.byName(true))
                        .map(Path::toString)
                        .collect(Collectors.joining(", ", "[", "]"))
                );
            }
            switch (basenameEvents.get(basename))
            {
                case ADDED ->
                {
                    events.unitAdded(basename, paths);
                }
                case CHANGED ->
                {
                    events.unitChanged(basename, getUnit(basename));
                }
                case REMOVED ->
                {
                    events.unitRemoved(basename);
                }
            }
        }
    }

    public interface Events
    {
        void unitAdded(String basename, Set<Path> paths);

        void unitChanged(String basename, Set<Path> paths);

        void unitRemoved(String basename);
    }
}
