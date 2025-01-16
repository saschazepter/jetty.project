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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner.Notification;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ScannerTest
{
    public WorkDir workDir;

    public Path _directory;
    private Scanner _scanner;
    private final BlockingQueue<Event> _queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Set<String>> _bulk = new LinkedBlockingQueue<>();

    @BeforeEach
    public void setupScanner() throws Exception
    {
        _directory = workDir.getEmptyPathDir();
        _scanner = new Scanner();
        _scanner.addDirectory(_directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename)
            {
                _queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename)
            {
                _queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename)
            {
                _queue.add(new Event(filename, Notification.ADDED));
            }
        });
        _scanner.addListener((Scanner.BulkListener)_bulk::add);

        _scanner.start();
        _scanner.scan();

        assertTrue(_queue.isEmpty());
        assertTrue(_bulk.isEmpty());
    }

    @AfterEach
    public void cleanup() throws Exception
    {
        LifeCycle.stop(_scanner);
    }

    static class Event
    {
        String _filename;
        Scanner.Notification _notification;

        public Event(String filename, Notification notification)
        {
            _filename = filename;
            _notification = notification;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Event that = (Event)obj;
            return _filename.equals(that._filename) && _notification == that._notification;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(_filename, _notification);
        }

        @Override
        public String toString()
        {
            return ("File: " + _filename + ":" + _notification);
        }
    }

    @Test
    public void testDepth() throws Exception
    {
        Path root = _directory.resolve("root");
        FS.ensureDirExists(root);
        FS.touch(root.resolve("foo.foo"));
        FS.touch(root.resolve("foo2.foo"));
        Path dir = root.resolve("xxx");
        FS.ensureDirExists(dir);
        FS.touch(dir.resolve("xxx.foo"));
        FS.touch(dir.resolve("xxx2.foo"));
        Path dir2 = dir.resolve("yyy");
        FS.ensureDirExists(dir2);
        FS.touch(dir2.resolve("yyy.foo"));
        FS.touch(dir2.resolve("yyy2.foo"));

        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        Scanner scanner = new Scanner();
        scanner.setScanInterval(0);
        scanner.setScanDepth(0);
        scanner.setReportDirs(true);
        scanner.setReportExistingFilesOnStartup(true);
        scanner.addDirectory(root);
        scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename)
            {
                queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename)
            {
                queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename)
            {
                queue.add(new Event(filename, Notification.ADDED));
            }
        });

        scanner.start();
        Event e = queue.take();
        assertNotNull(e);
        assertEquals(Notification.ADDED, e._notification);
        assertTrue(e._filename.endsWith(root.getFileName().toString()));
        queue.clear();
        scanner.stop();
        scanner.reset();

        //Depth one should report the dir itself and its file and dir direct children
        scanner.setScanDepth(1);
        scanner.addDirectory(root);
        scanner.start();
        assertEquals(4, queue.size());
        queue.clear();
        scanner.stop();
        scanner.reset();

        //Depth 2 should report the dir itself, all file children, xxx and xxx's children
        scanner.setScanDepth(2);
        scanner.addDirectory(root);
        scanner.start();

        assertEquals(7, queue.size());
        scanner.stop();
    }

    @Test
    public void testPatterns() throws Exception
    {
        // test include and exclude patterns
        Path root = _directory.resolve("proot");
        FS.ensureDirExists(root);

        Path ttt = root.resolve("ttt.txt");
        FS.touch(ttt);
        FS.touch(root.resolve("ttt.foo"));
        Path dir = root.resolve("xxx");
        FS.ensureDirExists(dir);

        Path x1 = dir.resolve("ttt.xxx");
        FS.touch(x1);
        Path x2 = dir.resolve("xxx.txt");
        FS.touch(x2);

        Path dir2 = dir.resolve("yyy");
        FS.ensureDirExists(dir2);
        Path y1 = dir2.resolve("ttt.yyy");
        FS.touch(y1);
        Path y2 = dir2.resolve("yyy.txt");
        FS.touch(y2);

        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        //only scan the *.txt files for changes
        Scanner scanner = new Scanner();
        IncludeExcludeSet<PathMatcher, Path> pattern = scanner.addDirectory(root);
        pattern.exclude(root.getFileSystem().getPathMatcher("glob:**/*.foo"));
        pattern.exclude(root.getFileSystem().getPathMatcher("glob:**/ttt.xxx"));
        scanner.setScanInterval(0);
        scanner.setScanDepth(2); //should never see any files from subdir yyy
        scanner.setReportDirs(false);
        scanner.setReportExistingFilesOnStartup(false);
        scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename)
            {
                queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename)
            {
                queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename)
            {
                queue.add(new Event(filename, Notification.ADDED));
            }
        });

        scanner.start();
        assertTrue(queue.isEmpty());

        Thread.sleep(1100); // make sure time in seconds changes
        FS.touch(ttt);
        FS.touch(x2);
        FS.touch(x1);
        FS.touch(y2);
        scanner.scan();
        scanner.scan(); //2 scans for file to be considered settled

        List<Event> results = new ArrayList<>();
        queue.drainTo(results);
        assertThat(results.size(), Matchers.equalTo(2));
        for (Event e : results)
        {
            assertTrue(e._filename.endsWith("ttt.txt") || e._filename.endsWith("xxx.txt"));
        }
    }

    @Test
    @Tag("Slow")
    public void testAddedChangeRemove() throws Exception
    {
        touch("a0");

        // takes 2 scans to notice a0 and check that it is stable
        _scanner.scan();
        _scanner.scan();

        Event event = _queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "Event should not be null");
        assertEquals(_directory.resolve("a0").toString(), event._filename);
        assertEquals(Notification.ADDED, event._notification);

        // add 3 more files
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        touch("a3");

        // not stable after 1 scan so should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        assertNull(event);

        // Keep a2 unstable and remove a3 before it stabilized
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");
        delete("a3");

        // only a1 is stable so it should be seen, a3 is deleted
        _scanner.scan();
        List<Event> actualEvents = new ArrayList<>();
        _queue.drainTo(actualEvents);
        assertEquals(2, actualEvents.size());
        Event a1 = new Event(_directory.resolve("a1").toString(), Notification.ADDED);
        Event a3 = new Event(_directory.resolve("a3").toString(), Notification.REMOVED);
        assertThat(actualEvents, Matchers.containsInAnyOrder(a1, a3));
        assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        assertNotNull(event);
        assertEquals(_directory.resolve("a2").toString(), event._filename);
        assertEquals(Notification.ADDED, event._notification);
        assertTrue(_queue.isEmpty());

        // touch a1 and a2
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        // not stable after 1scan so nothing should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        assertNull(event);

        // Keep a2 unstable
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        assertNotNull(event);
        assertEquals(_directory.resolve("a1").toString(), event._filename);
        assertEquals(Notification.CHANGED, event._notification);
        assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        assertNotNull(event);
        assertEquals(_directory.resolve("a2").toString(), event._filename);
        assertEquals(Notification.CHANGED, event._notification);
        assertTrue(_queue.isEmpty());

        // delete a1 and a2
        delete("a1");
        delete("a2");

        //Immediate notification of deletes.
        _scanner.scan();
        a1 = new Event(_directory.resolve("a1").toString(), Notification.REMOVED);
        Event a2 = new Event(_directory.resolve("a2").toString(), Notification.REMOVED);
        actualEvents = new ArrayList<>();
        _queue.drainTo(actualEvents);
        assertEquals(2, actualEvents.size());
        assertThat(actualEvents, Matchers.containsInAnyOrder(a1, a2));
        assertTrue(_queue.isEmpty());

        // recreate a2
        touch("a2");

        // a2 not stable yet, shouldn't be seen
        _scanner.scan();
        event = _queue.poll();
        assertNull(event);
        assertTrue(_queue.isEmpty());

        //Now a2 is reported as ADDED.
        _scanner.scan();
        event = _queue.poll();
        assertNotNull(event);
        assertEquals(_directory.resolve("a2").toString(), event._filename);
        assertEquals(Notification.ADDED, event._notification);
        assertTrue(_queue.isEmpty());
    }

    @Test
    public void testSizeChange() throws Exception
    {
        touch("tsc0");
        _scanner.scan();
        _scanner.scan();

        // takes 2 scans to notice tsc0 and check that it is stable.
        Event event = _queue.poll();
        assertNotNull(event);
        assertEquals(_directory.resolve("tsc0").toString(), event._filename);
        assertEquals(Notification.ADDED, event._notification);

        // Create a new file by writing to it.
        FileTime now = FileTime.from(Instant.now());
        Path file = _directory.resolve("st");
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
        {
            out.write('x');
            out.flush();
            FS.touch(file);
            Files.setLastModifiedTime(file, now);

            // Not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertNull(event);

            // Modify size only
            out.write('x');
            out.flush();
            Files.setLastModifiedTime(file, now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertNull(event);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            assertNotNull(event);
            assertEquals(_directory.resolve("st").toString(), event._filename);
            assertEquals(Notification.ADDED, event._notification);

            // Modify size only
            out.write('x');
            out.flush();
            Files.setLastModifiedTime(file, now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertNull(event);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            assertNotNull(event);
            assertEquals(_directory.resolve("st").toString(), event._filename);
            assertEquals(Notification.CHANGED, event._notification);
        }
    }

    private void delete(String string) throws IOException
    {
        Path file = _directory.resolve(string);
        Files.deleteIfExists(file);
    }

    private void touch(String string) throws IOException
    {
        Path file = _directory.resolve(string);
        FS.touch(file);
    }
}
