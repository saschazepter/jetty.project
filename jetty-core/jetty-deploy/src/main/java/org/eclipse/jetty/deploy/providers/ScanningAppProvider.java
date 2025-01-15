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

import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.internal.DeploymentUnits;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("Abstract Provider for loading webapps")
public abstract class ScanningAppProvider extends ContainerLifeCycle implements AppProvider, DeploymentUnits.Events
{
    private static final Logger LOG = LoggerFactory.getLogger(ScanningAppProvider.class);

    private DeploymentUnits _units = new DeploymentUnits();
    private Map<String, App> _appMap = new HashMap<>();

    private DeploymentManager _deploymentManager;
    private FilenameFilter _filenameFilter;
    private final List<Resource> _monitored = new CopyOnWriteArrayList<>();
    private int _scanInterval = 10;
    private Scanner _scanner;
    private boolean _useRealPaths;
    private boolean _deferInitialScan = false;

    private final Scanner.BulkListener _scannerBulkListener = new Scanner.BulkListener()
    {
        @Override
        public void pathsChanged(Set<Path> paths)
        {
            _units.processChanges(paths, ScanningAppProvider.this);
        }

        @Override
        public void filesChanged(Set<String> filenames)
        {
            // ignore, as we are using the pathsChanged() technique only.
        }
    };

    protected ScanningAppProvider()
    {
        this(null);
    }

    protected ScanningAppProvider(FilenameFilter filter)
    {
        _filenameFilter = filter;
        installBean(_appMap);
    }

    /**
     * @return True if the real path of the scanned files should be used for deployment.
     */
    public boolean isUseRealPaths()
    {
        return _useRealPaths;
    }

    /**
     * @param useRealPaths True if the real path of the scanned files should be used for deployment.
     */
    public void setUseRealPaths(boolean useRealPaths)
    {
        _useRealPaths = useRealPaths;
    }

    protected void setFilenameFilter(FilenameFilter filter)
    {
        if (isRunning())
            throw new IllegalStateException();
        _filenameFilter = filter;
    }

    /**
     * @return The index of currently deployed applications.
     */
    protected Map<String, App> getDeployedApps()
    {
        return _appMap;
    }

    /**
     * Called by the Scanner.DiscreteListener to create a new App object.
     * Isolated in a method so that it is possible to override the default App
     * object for specialized implementations of the AppProvider.
     *
     * @param path The file that the main point of deployment (eg: a context XML, a WAR file, a directory, etc)
     * @return The App object for this particular context.
     */
    protected App createApp(Path path)
    {
        App app = new App(_deploymentManager, this, path);
        if (LOG.isDebugEnabled())
            LOG.debug("{} creating {}", this, app);
        return app;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.doStart()", this.getClass().getSimpleName());
        if (_monitored.isEmpty())
            throw new IllegalStateException("No monitored dir specified");

        LOG.info("Deployment monitor in {} at intervals {}s", _monitored, getScanInterval());
        List<Path> files = new ArrayList<>();
        for (Resource resource : _monitored)
        {
            if (Resources.missing(resource))
            {
                LOG.warn("Does not exist: {}", resource);
                continue; // skip
            }

            // handle resource smartly
            for (Resource r: resource)
            {
                Path path = r.getPath();
                if (path == null)
                {
                    LOG.warn("Not based on FileSystem Path: {}", r);
                    continue; // skip
                }
                if (Files.isDirectory(path) || Files.isReadable(path))
                    files.add(resource.getPath());
                else
                    LOG.warn("Unsupported Path (not a directory and/or not readable): {}", r);
            }
        }

        _scanner = new Scanner(null, _useRealPaths);
        _scanner.setScanDirs(files);
        _scanner.setScanInterval(_scanInterval);
        _scanner.setFilenameFilter(_filenameFilter);
        _scanner.setReportDirs(true);
        _scanner.setScanDepth(1); //consider direct dir children of monitored dir
        _scanner.addListener(_scannerBulkListener);
        _scanner.setReportExistingFilesOnStartup(true);
        _scanner.setAutoStartScanning(!_deferInitialScan);
        addBean(_scanner);

        if (isDeferInitialScan())
        {
            // Setup listener to wait for Server in STARTED state, which
            // triggers the first scan of the monitored directories
            getDeploymentManager().getServer().addEventListener(
                new LifeCycle.Listener()
                {
                    @Override
                    public void lifeCycleStarted(LifeCycle event)
                    {
                        if (event instanceof Server)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Triggering Deferred Scan of {}", _monitored);
                            _scanner.startScanning();
                        }
                    }
                });
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_scanner != null)
        {
            removeBean(_scanner);
            _scanner.removeListener(_scannerBulkListener);
            _scanner = null;
        }
    }

    protected boolean exists(String path)
    {
        return _scanner.exists(path);
    }

    /**
     * Given a set of Paths that belong to the same unit of deployment,
     * pick the main Path that is actually the point of deployment.
     *
     * @param paths the set of paths that represent a single unit of deployment. (all paths in {@link Set} are of the same basename)
     * @return the specific path that represents the main point of deployment (eg: xml if it exists)
     */
    protected abstract Path getMainDeploymentPath(Set<Path> paths);

    public void unitAdded(String basename, Set<Path> paths)
    {
        Path mainDeploymentPath = getMainDeploymentPath(paths);
        App app = this.createApp(mainDeploymentPath);

        if (LOG.isDebugEnabled())
            LOG.debug("unitAdded {} -> {}: {}", basename, paths, app);

        if (app != null)
        {
            _appMap.put(basename, app);
            _deploymentManager.addApp(app);
        }
    }

    @Override
    public void unitChanged(String basename, Set<Path> paths)
    {
        App oldApp = _appMap.remove(basename);
        if (oldApp != null)
            _deploymentManager.removeApp(oldApp);

        Path mainDeploymentPath = getMainDeploymentPath(paths);
        App app = ScanningAppProvider.this.createApp(mainDeploymentPath);
        if (LOG.isDebugEnabled())
            LOG.debug("unitChanged {} -> {}: {}", basename, paths, app);
        if (app != null)
        {
            _appMap.put(basename, app);
            _deploymentManager.addApp(app);
        }
    }

    @Override
    public void unitRemoved(String basename)
    {
        App app = _appMap.remove(basename);
        if (LOG.isDebugEnabled())
            LOG.debug("unitRemoved {}: {}", basename, app);
        if (app != null)
        {
            _deploymentManager.removeApp(app);
        }
    }

    /**
     * Get the deploymentManager.
     *
     * @return the deploymentManager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    public Resource getMonitoredDirResource()
    {
        if (_monitored.isEmpty())
            return null;
        if (_monitored.size() > 1)
            throw new IllegalStateException();
        return _monitored.get(0);
    }

    public String getMonitoredDirName()
    {
        Resource resource = getMonitoredDirResource();
        return resource == null ? null : resource.toString();
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return _scanInterval;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    public void setMonitoredResources(List<Resource> resources)
    {
        _monitored.clear();
        if (resources == null)
            return;
        resources.stream().filter(Objects::nonNull).forEach(_monitored::add);
    }

    public List<Resource> getMonitoredResources()
    {
        return Collections.unmodifiableList(_monitored);
    }

    public void setMonitoredDirResource(Resource resource)
    {
        setMonitoredResources(Collections.singletonList(resource));
    }

    public void addScannerListener(Scanner.Listener listener)
    {
        _scanner.addListener(listener);
    }

    /**
     * @param dir Directory to scan for context descriptors or war files
     */
    public void setMonitoredDirName(String dir)
    {
        setMonitoredDirectories(Collections.singletonList(dir));
    }

    public void setMonitoredDirectories(Collection<String> directories)
    {
        try
        {
            List<Resource> resources = new ArrayList<>();
            for (String dir : directories)
            {
                resources.add(ResourceFactory.of(this).newResource(dir));
            }
            setMonitoredResources(resources);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Test if initial scan should be deferred.
     *
     * @return true if initial scan is deferred, false to have initial scan occur on startup of ScanningAppProvider.
     */
    public boolean isDeferInitialScan()
    {
        return _deferInitialScan;
    }

    /**
     * Flag to control initial scan behavior.
     *
     * <ul>
     *     <li>{@code true} - to have initial scan deferred until the {@link Server} component
     *     has reached it's STARTED state.<br>
     *     Note: any failures in a deploy will not fail the Server startup in this mode.</li>
     *     <li>{@code false} - (default value) to have initial scan occur as normal on
     *     ScanningAppProvider startup.</li>
     * </ul>
     *
     * @param defer true to defer initial scan, false to have initial scan occur on startup of ScanningAppProvider.
     */
    public void setDeferInitialScan(boolean defer)
    {
        _deferInitialScan = defer;
    }

    public void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
    }

    @ManagedOperation(value = "Scan the monitored directories", impact = "ACTION")
    public void scan()
    {
        LOG.info("Performing scan of monitored directories: {}",
            getMonitoredResources().stream().map((r) -> r.getURI().toASCIIString())
                .collect(Collectors.joining(", ", "[", "]"))
        );
        _scanner.nudge();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", this.getClass(), hashCode(), _monitored);
    }
}
