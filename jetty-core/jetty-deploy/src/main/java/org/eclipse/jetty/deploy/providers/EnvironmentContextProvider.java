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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.LoggerFactory;

/**
 * The webapps directory scanning provider.
 * <p>This provider scans one or more directories (typically "webapps") for contexts to
 * deploy, which may be:
 * </p>
 * <ul>
 * <li>A standard WAR file (must end in ".war")</li>
 * <li>A directory containing an expanded WAR file</li>
 * <li>A directory containing static content</li>
 * <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * <p>To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans:
 * </p>
 * <ul>
 * <li>Hidden files (starting with {@code "."}) are ignored</li>
 * <li>Directories with names ending in {@code ".d"} are ignored</li>
 * <li>Property files with names ending in {@code ".properties"} are not deployed.</li>
 * <li>If a directory and a WAR file exist (eg: {@code foo/} and {@code foo.war}) then the directory is assumed to be
 * the unpacked WAR and only the WAR is deployed (which may reused the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist (eg: {@code foo/} and {@code foo.xml}) then the directory is assumed to be
 * an unpacked WAR and only the XML is deployed (which may used the directory in its configuration)</li>
 * <li>If a WAR file and a matching XML exist (eg: {@code foo.war} and {@code foo.xml}) then the WAR is assumed to
 * be configured by the XML and only the XML is deployed.
 * </ul>
 * <p>For XML configured contexts, the ID map will contain a reference to the {@link Server} instance called "Server" and
 * properties for the webapp file such as "jetty.webapp" and directory as "jetty.webapps".
 * The properties will be initialized with:
 * </p>
 * <ul>
 * <li>The properties set on the application via {@link App#getProperties()}</li>
 * <li>The app specific properties file {@code webapps/<webapp-name>.properties}</li>
 * <li>The environment specific properties file {@code webapps/<environment-name>[-zzz].properties}</li>
 * <li>The {@link Attributes} from the {@link Environment}</li>
 * </ul>
 */
@ManagedObject("Provider for start-up deployment of webapps based on presence in directory")
public class EnvironmentContextProvider extends ScanningAppProvider
{
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(EnvironmentContextProvider.class);
    private String defaultEnvironmentName;

    public EnvironmentContextProvider()
    {
        super();
        setFilenameFilter(new Filter());
        setScanInterval(0);
    }

    public String getDefaultEnvironmentName()
    {
        if (defaultEnvironmentName == null)
        {
            return Environment.getAll().stream()
                .map(Environment::getName)
                .max(Deployable.ENVIRONMENT_COMPARATOR)
                .orElse(null);
        }
        return defaultEnvironmentName;
    }

    public void setDefaultEnvironmentName(String name)
    {
        this.defaultEnvironmentName = name;
    }

    private static Map<String, String> asProperties(Attributes attributes)
    {
        Map<String, String> props = new HashMap<>();
        attributes.getAttributeNameSet().stream()
            .map((name) ->
            {
                // undo old prefixed entries
                if (name.startsWith(Deployable.ATTRIBUTE_PREFIX))
                    return name.substring(Deployable.ATTRIBUTE_PREFIX.length());
                else
                    return name;
            })
            .forEach((name) -> props.put(name, Objects.toString(attributes.getAttribute(name))));
        return props;
    }

    @Override
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Environment environment = Environment.get(app.getEnvironmentName());

        if (environment == null)
        {
            LOG.warn("Environment [{}] is not available for app [{}].  The available environments are: {}",
                app.getEnvironmentName(),
                app,
                Environment.getAll().stream()
                    .map(Environment::getName)
                    .collect(Collectors.joining(",", "[", "]"))
            );
            return null;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("createContextHandler {} in {}", app, environment);

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(environment.getClassLoader());

            // Create de-aliased file
            Path path = app.getPath().toRealPath();
            if (!Files.exists(path))
                throw new IllegalStateException("App resource does not exist " + path);

            // prepare app attributes to use for app deployment
            Attributes appAttributes = initAttributes(environment, app);

            Object context = null;

            // check if there is a specific ContextHandler type to create set in the
            // properties associated with the webapp. If there is, we create it _before_
            // applying the environment xml file.
            String contextHandlerClassName = (String)appAttributes.getAttribute(Deployable.CONTEXT_HANDLER_CLASS);
            if (contextHandlerClassName != null)
                context = Class.forName(contextHandlerClassName).getDeclaredConstructor().newInstance();

            // Collect the optional environment context xml files.
            // Order them according to the name of their property key names.
            List<Path> sortedEnvXmlPaths = appAttributes.getAttributeNameSet()
                .stream()
                .filter(k -> k.startsWith(Deployable.ENVIRONMENT_XML))
                .map(k -> Path.of((String)appAttributes.getAttribute(k)))
                .sorted()
                .toList();

            // apply each environment context xml file
            for (Path envXmlPath : sortedEnvXmlPaths)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Applying environment specific context file {}", envXmlPath);
                context = applyXml(context, envXmlPath, environment, appAttributes);
            }

            // Handle a context XML file
            if (FileID.isXml(path))
            {
                context = applyXml(context, path, environment, appAttributes);

                // Look for the contextHandler itself
                ContextHandler contextHandler = null;
                if (context instanceof ContextHandler c)
                    contextHandler = c;
                else if (context instanceof Supplier<?> supplier)
                {
                    Object nestedContext = supplier.get();
                    if (nestedContext instanceof ContextHandler c)
                        contextHandler = c;
                }
                if (contextHandler == null)
                    throw new IllegalStateException("Unknown context type of " + context);

                return contextHandler;
            }
            // Otherwise it must be a directory or an archive
            else if (!Files.isDirectory(path) && !FileID.isWebArchive(path))
            {
                throw new IllegalStateException("unable to create ContextHandler for " + app);
            }

            // Build the web application if necessary
            if (context == null)
            {
                contextHandlerClassName = (String)environment.getAttribute(Deployable.CONTEXT_HANDLER_CLASS);
                if (StringUtil.isBlank(contextHandlerClassName))
                    throw new IllegalStateException("No ContextHandler classname for " + app);
                Class<?> contextHandlerClass = Loader.loadClass(contextHandlerClassName);
                if (contextHandlerClass == null)
                    throw new IllegalStateException("Unknown ContextHandler class " + contextHandlerClassName + " for " + app);

                context = contextHandlerClass.getDeclaredConstructor().newInstance();
            }

            //set a backup value for the path to the war in case it hasn't already been set
            appAttributes.setAttribute(Deployable.WAR, path.toString());
            return initializeContextHandler(context, path, appAttributes);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Deprecated
    public Map<String, String> getProperties(Environment environment)
    {
        return asProperties(environment);
    }

    public void loadProperties(Environment environment, Path path) throws IOException
    {
        try (InputStream inputStream = Files.newInputStream(path))
        {
            loadProperties(environment, inputStream);
        }
    }

    public void loadProperties(Environment environment, Resource resource) throws IOException
    {
        try (InputStream inputStream = IOResources.asInputStream(resource))
        {
            loadProperties(environment, inputStream);
        }
    }

    public void loadPropertiesFromString(Environment environment, String path) throws IOException
    {
        loadProperties(environment, Path.of(path));
    }

    protected Object applyXml(Object context, Path xml, Environment environment, Attributes attributes) throws Exception
    {
        if (!FileID.isXml(xml))
            return null;

        XmlConfiguration xmlc = new XmlConfiguration(ResourceFactory.of(this).newResource(xml), null, asProperties(attributes))
        {
            @Override
            public void initializeDefaults(Object context)
            {
                super.initializeDefaults(context);
                EnvironmentContextProvider.this.initializeContextHandler(context, xml, attributes);
            }
        };

        xmlc.getIdMap().put("Environment", environment.getName());
        xmlc.setJettyStandardIdsAndProperties(getDeploymentManager().getServer(), xml);

        // Put all Environment attributes into XMLC as properties that can be used.
        attributes.getAttributeNameSet()
            .stream()
            .filter(k -> !k.startsWith("jetty.home") &&
                !k.startsWith("jetty.base") &&
                !k.startsWith("jetty.webapps"))
            .forEach(k ->
            {
                String v = Objects.toString(attributes.getAttribute(k));
                xmlc.getProperties().put(k, v);
            });

        // Run configure against appropriate classloader.
        ClassLoader xmlClassLoader = getXmlClassLoader(environment, xml);
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(xmlClassLoader);

        try
        {
            // Create or configure the context
            if (context == null)
                return xmlc.configure();

            return xmlc.configure(context);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    /**
     * Return a ClassLoader that can load a {@link Environment#CORE} based webapp
     * that is entirely defined within the {@code webapps/} directory.
     *
     * <p>The resulting ClassLoader consists of the following entries:</p>
     * <ol>
     * <li>The java archive {@code <basename>.jar}</li>
     * <li>The java archives {@code <basename>.d/lib/*.jar}</li>
     * <li>The directory {@code <basename>.d/classes/}</li>
     * </ol>
     *
     * @param path to XML defining this webapp, must be absolute, and cannot be in root directory of drive.
     * filename of XML will be used to determine the {@code <basename>} of the other entries in this
     * ClassLoader.
     * @return the classloader for this CORE environment webapp.
     * @throws IOException if unable to apply to create classloader.
     */
    protected ClassLoader findCoreContextClassLoader(Path path) throws IOException
    {
        Path webapps = path.getParent();
        String basename = FileID.getBasename(path);
        List<URL> urls = new ArrayList<>();

        // Is there a matching jar file?
        // TODO: both files can be there depending on FileSystem, is this still sane?
        // TODO: what about other capitalization? eg: ".Jar" ?
        Path contextJar = webapps.resolve(basename + ".jar");
        if (!Files.exists(contextJar))
            contextJar = webapps.resolve(basename + ".JAR");
        if (Files.exists(contextJar))
            urls.add(contextJar.toUri().toURL());

        // Is there a matching lib directory?
        Path libDir = webapps.resolve(basename + ".d/lib");
        if (Files.exists(libDir) && Files.isDirectory(libDir))
        {
            try (Stream<Path> paths = Files.list(libDir))
            {
                paths.filter(FileID::isJavaArchive)
                    .map(Path::toUri)
                    .forEach(uri ->
                    {
                        try
                        {
                            urls.add(uri.toURL());
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }

        // Is there a matching lib directory?
        Path classesDir = webapps.resolve(basename + ".d/classes");
        if (Files.exists(classesDir) && Files.isDirectory(libDir))
            urls.add(classesDir.toUri().toURL());

        if (LOG.isDebugEnabled())
            LOG.debug("Core classloader for {}", urls);

        if (urls.isEmpty())
            return null;
        return new URLClassLoader(urls.toArray(new URL[0]), Environment.CORE.getClassLoader());
    }

    protected ContextHandler initializeContextHandler(Object context, Path path, Attributes attributes)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("initializeContextHandler {}", context);
        // find the ContextHandler
        ContextHandler contextHandler;
        if (context instanceof ContextHandler handler)
            contextHandler = handler;
        else if (Supplier.class.isAssignableFrom(context.getClass()))
        {
            @SuppressWarnings("unchecked")
            Supplier<ContextHandler> provider = (Supplier<ContextHandler>)context;
            contextHandler = provider.get();
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not a context {}", context);
            return null;
        }

        assert contextHandler != null;

        initializeContextPath(contextHandler, path);

        if (Files.isDirectory(path))
        {
            ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
            contextHandler.setBaseResource(resourceFactory.newResource(path));
        }

        // pass through properties as attributes directly
        attributes.getAttributeNameSet().stream()
            .filter((name) -> name.startsWith(Deployable.ATTRIBUTE_PREFIX))
            .map((name) -> name.substring(Deployable.ATTRIBUTE_PREFIX.length()))
            .forEach((name) -> contextHandler.setAttribute(name, attributes.getAttribute(name)));

        String contextPath = (String)attributes.getAttribute(Deployable.CONTEXT_PATH);
        if (StringUtil.isNotBlank(contextPath))
            contextHandler.setContextPath(contextPath);

        if (context instanceof Deployable deployable)
            deployable.initializeDefaults(attributes);

        return contextHandler;
    }

    protected void initializeContextPath(ContextHandler context, Path path)
    {
        // Strip any 3 char extension from non directories
        String basename = FileID.getBasename(path);
        String contextPath = basename;

        // special case of archive (or dir) named "root" is / context
        if (contextPath.equalsIgnoreCase("root"))
        {
            contextPath = "/";
        }
        // handle root with virtual host form
        else if (StringUtil.asciiStartsWithIgnoreCase(contextPath, "root-"))
        {
            int dash = contextPath.indexOf('-');
            String virtual = contextPath.substring(dash + 1);
            context.setVirtualHosts(Arrays.asList(virtual.split(",")));
            contextPath = "/";
        }

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        // Set the display name and context Path
        context.setDisplayName(basename);
        context.setContextPath(contextPath);
    }

    protected boolean isDeployable(Path path)
    {
        String basename = FileID.getBasename(path);

        //is the file that changed a directory?
        if (Files.isDirectory(path))
        {
            // deploy if there is not a .xml or .war file of the same basename?
            return !Files.exists(path.getParent().resolve(basename + ".xml")) &&
                !Files.exists(path.getParent().resolve(basename + ".XML")) &&
                !Files.exists(path.getParent().resolve(basename + ".war")) &&
                !Files.exists(path.getParent().resolve(basename + ".WAR"));
        }

        // deploy if it is a .war and there is not a .xml for of the same basename
        if (FileID.isWebArchive(path))
        {
            // if a .xml file exists for it
            return !Files.exists(path.getParent().resolve(basename + ".xml")) &&
                !Files.exists(path.getParent().resolve(basename + ".XML"));
        }

        // otherwise only deploy an XML
        return FileID.isXml(path);
    }

    @Override
    protected void pathAdded(Path path) throws Exception
    {
        if (isDeployable(path))
            super.pathAdded(path);
    }

    @Override
    protected void pathChanged(Path path) throws Exception
    {
        if (isDeployable(path))
            super.pathChanged(path);
    }

    /**
     * Get the ClassLoader appropriate for applying Jetty XML.
     * @param environment the environment to use
     * @param xml the path to the XML
     * @return the appropriate ClassLoader.
     * @throws IOException if unable to create the ClassLoader
     */
    private ClassLoader getXmlClassLoader(Environment environment, Path xml) throws IOException
    {
        if (Environment.CORE.equals(environment))
        {
            // this XML belongs to a CORE deployment.
            return findCoreContextClassLoader(xml);
        }
        else
        {
            return environment.getClassLoader();
        }
    }

    private Attributes initAttributes(Environment environment, App app) throws IOException
    {
        Attributes attributes = new Attributes.Mapped();

        // Start appAttributes with Attributes from Environment
        environment.getAttributeNameSet().forEach((key) ->
            attributes.setAttribute(key, environment.getAttribute(key)));

        // TODO: double check if an empty environment name makes sense. Will the default environment name?
        String env = app.getEnvironmentName();

        if (StringUtil.isNotBlank(env))
        {
            // Load environment specific properties files
            Path parent = app.getPath().getParent();
            Properties envProps = loadEnvironmentProperties(parent, env);

            envProps.stringPropertyNames().forEach(
                k -> attributes.setAttribute(k, envProps.getProperty(k))
            );
        }

        // Overlay the app properties
        app.getProperties().forEach(attributes::setAttribute);

        return attributes;
    }

    /**
     * Load all of the {@link Environment} specific {@code <env-name>[-<name>].properties} files
     * found in the directory provided.
     *
     * <p>
     *     All found properties files are first sorted by filename, then loaded one by one into
     *     a single {@link Properties} instance.
     * </p>
     *
     * @param directory the directory to load environment properties from.
     * @param env the environment name
     */
    private Properties loadEnvironmentProperties(Path directory, String env) throws IOException
    {
        Properties props = new Properties();
        List<Path> envPropertyFiles = new ArrayList<>();

        // Get all environment specific properties files for this environment,
        // order them according to the lexical ordering of the filenames
        try (Stream<Path> paths = Files.list(directory))
        {
            envPropertyFiles = paths.filter(Files::isRegularFile)
                .map(directory::relativize)
                .filter(p ->
                {
                    String name = p.getName(0).toString();
                    if (!name.endsWith(".properties"))
                        return false;
                    if (!name.startsWith(env))
                        return false;
                    return true;
                }).sorted().toList();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Environment property files {}", envPropertyFiles);

        // Load each *.properties file
        for (Path file : envPropertyFiles)
        {
            Path resolvedFile = directory.resolve(file);
            if (Files.exists(resolvedFile))
            {
                Properties tmp = new Properties();
                try (InputStream stream = Files.newInputStream(resolvedFile))
                {
                    tmp.load(stream);
                    //put each property into our substitution pool
                    tmp.stringPropertyNames().forEach(k -> props.put(k, tmp.getProperty(k)));
                }
            }
        }

        return props;
    }

    private void loadProperties(Environment environment, InputStream inputStream) throws IOException
    {
        Properties props = new Properties();
        props.load(inputStream);
        props.stringPropertyNames().forEach((name) ->
            environment.setAttribute(name, props.getProperty(name)));
    }

    /**
     * Builder of a deployment configuration for a specific {@link Environment}.
     *
     * <p>
     *     Results in {@link Attributes} for {@link Environment} containing the
     *     deployment configuration (as {@link Deployable} keys) that is applied to all deployable
     *     apps belonging to that {@link Environment}.
     * </p>
     */
    public static class EnvBuilder
    {
        // Using setters in this class to allow jetty-xml <Set name="" property="">
        // syntax to skip setting of an environment attribute if property is unset,
        // allowing the in code values to be same defaults as they are in embedded-jetty.

        private final Environment environment;

        public EnvBuilder(String name)
        {
            environment = Environment.ensure(name);
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} attribute.
         *
         * @param configurations The configuration class names as a comma separated list
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String configurations)
        {
            setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
         *
         * @param configurations The configuration class names.
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String[] configurations)
        {
            if (configurations == null)
                environment.removeAttribute(Deployable.CONFIGURATION_CLASSES);
            else
                environment.setAttribute(Deployable.CONFIGURATION_CLASSES, configurations);
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONTAINER_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning container jars
         * @see Deployable#CONTAINER_SCAN_JARS
         */
        public void setContainerScanJarPattern(String pattern)
        {
            environment.setAttribute(Deployable.CONTAINER_SCAN_JARS, pattern);
        }

        /**
         * The name of the class that this environment uses to create {@link ContextHandler}
         * instances (can be class that implements {@code java.util.function.Supplier<Handler>}
         * as well).
         *
         * @param classname the classname for this environment's context deployable.
         * @see Deployable#CONTEXT_HANDLER_CLASS
         */
        public void setContextHandlerClass(String classname)
        {
            environment.setAttribute(Deployable.CONTEXT_HANDLER_CLASS, classname);
        }

        /**
         * Set the defaultsDescriptor.
         * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} attribute.
         *
         * @param defaultsDescriptor the defaultsDescriptor to set
         * @see Deployable#DEFAULTS_DESCRIPTOR
         */
        public void setDefaultsDescriptor(String defaultsDescriptor)
        {
            environment.setAttribute(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
        }

        /**
         * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} attribute.
         *
         * @param extractWars the extractWars to set
         * @see Deployable#EXTRACT_WARS
         */
        public void setExtractWars(boolean extractWars)
        {
            environment.setAttribute(Deployable.EXTRACT_WARS, extractWars);
        }

        /**
         * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} attribute.
         *
         * @param parentLoaderPriority the parentLoaderPriority to set
         * @see Deployable#PARENT_LOADER_PRIORITY
         */
        public void setParentLoaderPriority(boolean parentLoaderPriority)
        {
            environment.setAttribute(Deployable.PARENT_LOADER_PRIORITY, parentLoaderPriority);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_EXCLUSION_PATTERN} property.
         *
         * @param pattern The regex pattern to exclude ServletContainerInitializers from executing
         * @see Deployable#SCI_EXCLUSION_PATTERN
         */
        public void setServletContainerInitializerExclusionPattern(String pattern)
        {
            environment.setAttribute(Deployable.SCI_EXCLUSION_PATTERN, pattern);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_ORDER} property.
         *
         * @param order The ordered list of ServletContainerInitializer classes to run
         * @see Deployable#SCI_ORDER
         */
        public void setServletContainerInitializerOrder(String order)
        {
            environment.setAttribute(Deployable.SCI_ORDER, order);
        }

        /**
         * This is equivalent to setting the {@link Deployable#WEBINF_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning web-inf jars
         * @see Deployable#WEBINF_SCAN_JARS
         */
        public void setWebInfScanJarPattern(String pattern)
        {
            environment.setAttribute(Deployable.WEBINF_SCAN_JARS, pattern);
        }
    }

    public class Filter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            if (dir == null || !dir.canRead())
                return false;

            // Accept XML files and WARs
            if (FileID.isXml(name) || FileID.isWebArchive(name))
                return true;

            Path path = dir.toPath().resolve(name);

            // Ignore any other file that are not directories
            if (!Files.isDirectory(path))
                return false;

            // Don't deploy monitored resources
            if (getMonitoredResources().stream().map(Resource::getPath).anyMatch(path::equals))
                return false;

            // Ignore hidden directories
            if (name.startsWith("."))
                return false;

            String lowerName = name.toLowerCase(Locale.ENGLISH);

            // is it a nominated config directory
            if (lowerName.endsWith(".d"))
                return false;

            // ignore source control directories
            if ("cvs".equals(lowerName) || "cvsroot".equals(lowerName))
                return false;

            // ignore directories that have sibling war or XML file
            if (Files.exists(dir.toPath().resolve(name + ".war")) ||
                Files.exists(dir.toPath().resolve(name + ".WAR")) ||
                Files.exists(dir.toPath().resolve(name + ".xml")) ||
                Files.exists(dir.toPath().resolve(name + ".XML")))
                return false;

            return true;
        }
    }
}
