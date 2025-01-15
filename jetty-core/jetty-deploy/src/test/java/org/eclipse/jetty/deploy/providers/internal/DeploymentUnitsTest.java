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
import java.util.Set;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(WorkDirExtension.class)
public class DeploymentUnitsTest
{
    public WorkDir workDir;

    @Test
    public void testNewXmlOnly() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        DeploymentUnits units = new DeploymentUnits();
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
    }

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
}
