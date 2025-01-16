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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.providers.Unit;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class DeploymentUnitsTest
{
    public WorkDir workDir;

    /*
     * foo.war
     * foo.xml
     * bar.war
     * bar.xml
     *
     * Discrete Listener (get 1 path) - 4 add events. (no sorting)
     * Bulk Listener (get 4 paths) - 2 unit add events. (bar before foo)
     *
     * Change bar.xml
     * Discrete Listener - 1 change event
     * Bulk Listener - 1 change event
     *
     * Change bar.war
     * Discrete Listener - 0 change events
     * Bulk Listener - 1 change event (deploys bar.xml)
     *
     * ---
     * ChangeSet #1
     *   bar.xml (added)
     *   bar/ (added)
     *   foo.xml (added)
     *   foo.properties (added)
     *   ee8.xml // (ENV)
     *   ee8.properties
     *   ee8-blah.xml
     *
     *   eventChangedUnits (List<Unit>)
     *     Unit[0] - "bar"|ADDED - (bar.xml|ADDED, bar/|ADDED)
     *     Unit[1] - "foo"|ADDED - (foo.xml|ADDED, foo.properties|ADDED)
     *
     * ChangeSet #2
     *   bar.properties (added)
     *   bar.xml (changed)
     *   bar/ (removed)
     *   foo.properties (changed)
     *
     *   eventChangedUnits (List<Unit>)
     *     Unit[0] - "bar"|CHANGED (bar.xml|CHANGED, bar.properties|CHANGED)
     *     Unit[1] - "foo"|CHANGED (foo.xml|UNCHANGED, foo.properties|CHANGED)
     *
     * ChangeSet #3
     *   foo.properties (removed)
     *
     *   eventChangedUnits (List<Unit>)
     *     Unit[0] - "foo"|CHANGED (foo.xml|UNCHANGED)
     *
     * ChangeSet #4
     *   foo.xml (removed)
     *
     *   eventChangedUnits (List<Unit>)
     *     Unit[0] - "foo"|REMOVED (<empty>)
     *
     *
     *
     */


    @Test
    public void testNewXmlOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), contains(xml));
        });
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        units.pathsChanged(changeSet);
    }

    /*
    @Test
    public void testXmlThenRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();

        // Initial deployment.
        units.processChanges(Set.of(xml), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(1));
                assertThat("paths", paths, contains(xml));
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitChanged");
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });

        // Removed deployment.
        Files.deleteIfExists(xml);
        units.processChanges(Set.of(xml), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitAdded");
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitChanged");
            }

            @Override
            public void unitRemoved(String basename)
            {
                assertThat("basename", basename, is("bar"));
            }
        });
    }

    @Test
    public void testNewXmlAndWarOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();
        units.processChanges(Set.of(xml, war), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(2));
                assertThat("paths", paths, containsInAnyOrder(xml, war));
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitChanged");
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });
    }

    @Test
    public void testXmlAndWarWithXmlUpdate() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();

        // Trigger first set of changes (both files)
        units.processChanges(Set.of(xml, war), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(2));
                assertThat("paths", paths, containsInAnyOrder(xml, war));
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitChanged");
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });

        // Trigger second set of changes (only xml)
        units.processChanges(Set.of(xml), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitAdded");
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(2));
                assertThat("paths", paths, containsInAnyOrder(xml, war));
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });
    }

    @Test
    public void testXmlAndWarWithXmlRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();

        // Trigger first set of changes (both files)
        units.processChanges(Set.of(xml, war), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(2));
                assertThat("paths", paths, containsInAnyOrder(xml, war));
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitChanged");
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });

        // Trigger second set of changes (war & xml), XML is deleted
        Files.deleteIfExists(xml);
        units.processChanges(Set.of(war, xml), new DeploymentUnits.Events()
        {
            @Override
            public void unitAdded(String basename, Set<Path> paths)
            {
                fail("Should not cause a unitAdded");
            }

            @Override
            public void unitChanged(String basename, Set<Path> paths)
            {
                assertThat("basename", basename, is("bar"));
                assertThat("paths.size", paths.size(), is(1));
                assertThat("paths", paths, containsInAnyOrder(war));
            }

            @Override
            public void unitRemoved(String basename)
            {
                fail("Should not cause a unitRemoved");
            }
        });
    }
     */
}
