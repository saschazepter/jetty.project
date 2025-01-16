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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

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
        units.getUnits().forEach(Unit::resetStates);
    }

    @Test
    public void testXmlThenRemoved() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("foo.xml");
        Files.writeString(xml, "XML for foo", UTF_8);

        DeploymentUnits units = new DeploymentUnits();

        // Initial deployment.
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("foo"));
            assertThat("changedUnit[foo].state", unit.getState(), is(Unit.State.ADDED));
            assertThat("changedUnit[foo].paths", unit.getPaths().keySet(), contains(xml));
            assertThat("changedUnit[foo].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.ADDED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);

        // Removed deployment.
        Files.deleteIfExists(xml);
        changeSet.clear();
        changeSet.put(xml, Scanner.Notification.REMOVED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("foo"));
            assertThat("changedUnit[foo].state", unit.getState(), is(Unit.State.REMOVED));
            assertThat("changedUnit[foo].paths", unit.getPaths().keySet(), contains(xml));
            assertThat("changedUnit[foo].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.REMOVED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);
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

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.ADDED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);
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

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.ADDED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);

        // Change/Touch war
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.CHANGED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.UNCHANGED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.CHANGED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);
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

        // Initial deployment
        Map<Path, Scanner.Notification> changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.ADDED);
        changeSet.put(war, Scanner.Notification.ADDED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.ADDED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.ADDED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);

        // Change/Touch war and xml
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.CHANGED);
        changeSet.put(xml, Scanner.Notification.CHANGED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.CHANGED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.CHANGED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.CHANGED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);

        // Delete XML
        Files.deleteIfExists(xml);
        changeSet = new HashMap<>();
        changeSet.put(xml, Scanner.Notification.REMOVED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.CHANGED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(xml, war));
            assertThat("changedUnit[bar].paths[xml].state", unit.getPaths().get(xml), is(Unit.State.REMOVED));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.UNCHANGED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);

        // Delete WAR
        Files.deleteIfExists(war);
        changeSet = new HashMap<>();
        changeSet.put(war, Scanner.Notification.REMOVED);
        units.setListener(changedUnits ->
        {
            assertThat("changedUnits.size", changedUnits.size(), is(1));
            Unit unit = changedUnits.get(0);
            assertThat("changedUnit[0].basename", unit.getBaseName(), is("bar"));
            assertThat("changedUnit[bar].state", unit.getState(), is(Unit.State.REMOVED));
            assertThat("changedUnit[bar].paths", unit.getPaths().keySet(), containsInAnyOrder(war));
            assertThat("changedUnit[bar].paths[war].state", unit.getPaths().get(war), is(Unit.State.REMOVED));
        });
        units.pathsChanged(changeSet);
        units.getUnits().forEach(Unit::resetStates);
    }
}
