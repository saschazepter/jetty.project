//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.util.IncludeExcludeSet;

public class IncludeExcludeConnectionStatistics extends ConnectionStatistics
{
    private final IncludeExcludeSet<Class<? extends Connection>, Connection> _set = new IncludeExcludeSet<>(ConnectionSet.class);

    public void include(String className) throws ClassNotFoundException
    {
        _set.include(connectionForName(className));
    }

    public void include(Class<? extends Connection> clazz)
    {
        _set.include(clazz);
    }

    public void exclude(String className) throws ClassNotFoundException
    {
        _set.exclude(connectionForName(className));
    }

    public void exclude(Class<? extends Connection> clazz)
    {
        _set.exclude(clazz);
    }

    private Class<? extends Connection> connectionForName(String className) throws ClassNotFoundException
    {
        Class<?> aClass = Class.forName(className);
        if (!Connection.class.isAssignableFrom(aClass))
            throw new IllegalArgumentException("Class is not a Connection");

        @SuppressWarnings("unchecked")
        Class<? extends Connection> connectionClass = (Class<? extends Connection>)aClass;
        return connectionClass;
    }

    @Override
    public void onOpened(Connection connection)
    {
        if (_set.test(connection))
            super.onOpened(connection);
    }

    @Override
    public void onClosed(Connection connection)
    {
        if (_set.test(connection))
            super.onClosed(connection);
    }

    public static class ConnectionSet extends AbstractSet<Class<? extends Connection>> implements Predicate<Connection>
    {
        private final Set<Class<? extends Connection>> set = new HashSet<>();

        @Override
        public boolean add(Class<? extends Connection> aClass)
        {
            return set.add(aClass);
        }

        @Override
        public boolean remove(Object o)
        {
            return set.remove(o);
        }

        @Override
        public Iterator<Class<? extends Connection>> iterator()
        {
            return set.iterator();
        }

        @Override
        public int size()
        {
            return set.size();
        }

        @Override
        public boolean test(Connection connection)
        {
            if (connection == null)
                return false;
            return set.stream().anyMatch(c -> c.isAssignableFrom(connection.getClass()));
        }
    }
}