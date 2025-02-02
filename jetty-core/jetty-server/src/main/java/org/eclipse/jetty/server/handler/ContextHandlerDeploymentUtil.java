package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;

public class ContextHandlerDeploymentUtil
{
    public interface Listener
    {
        /**
         * Before the ContextHandler is added to the ContextHandlerCollection.
         * <p>
         * A place to modify the ContextHandler collection, even wrap it (with returning a different object)
         */
        default ContextHandler onBeforeDeploy(ContextHandler contextHandler)
        {
            return contextHandler;
        }

        /**
         * The ContextHandler was added to the ContextHandlerCollection.
         * <p>
         * The context-path (and virtual hosts) is live in the ContextHandlerCollection.
         * The ContextHandler is marked as unavailable, and is not accepting requests (requests to ContextHandler will return 503)
         */
        default void onAfterDeploy(ContextHandler contextHandler)
        {
        }

        /**
         * The ContextHandler is about to be started.
         * <p>
         * This is an opportunity to participate in the startup behaviors of the ContextHandler.
         * <p>
         * Essentially executes before ContextHandler.doStart()
         */
        default void onBeforeStart(ContextHandler contextHandler)
        {
        }

        /**
         * The ContextHandler has been started.
         * <p>
         * The ContextHandler is now accepting requests.
         * <p>
         * Essentially executes after ContextHandler.doStart()
         */
        default void onAfterStart(ContextHandler contextHandler)
        {
        }

        /**
         * The ContextHandler failed to become available.
         * <p>
         * The failure could be due to a Throwable, this will let you know the cause if present.
         */
        default void onFailedStart(ContextHandler contextHandler, Throwable cause)
        {
        }

        /**
         * The ContextHandler is about to be stopped.
         * <p>
         * This is where you act on a running ContextHandler, before any stop/shutdown/destroy actions on it are executed.
         * <p>
         * Essentially executes before ContextHandler.doStop()
         */
        default void onBeforeStop(ContextHandler contextHandler)
        {
        }

        /**
         * The ContextHandler has been stopped.
         * <p>
         * The ContextHandler is set to unavailable, and is not accepting requests (requests to ContextHandler will return 503)
         * This can be too late to obtain some state from the ContextHandler implementation as the context has been
         * and various components might no longer be present to interrogate.
         * <p>
         * Essentially executes after ContextHandler.doStop()
         */
        default void onAfterStop(ContextHandler contextHandler)
        {
        }

        /**
         * The ContextHandler has be removed from the ContextHandlerCollection.
         */
        default void onUndeploy(ContextHandler contextHandler)
        {
        }
    }

    /**
     * Add a deployment related listener to a ContextHandlerCollection.
     *
     * @param contextHandlerCollection the collection to listen on
     * @param listener the events to report on
     */
    public static void addListener(ContextHandlerCollection contextHandlerCollection, ContextHandlerDeploymentUtil.Listener listener)
    {
        contextHandlerCollection.addEventListener(new ListenerImpl(contextHandlerCollection, listener));
    }

    private static class ListenerImpl implements Container.Listener, LifeCycle.Listener
    {
        private final ContextHandlerCollection contextHandlerCollection;
        private final ContextHandlerDeploymentUtil.Listener listener;

        public ListenerImpl(ContextHandlerCollection contextHandlerCollection, ContextHandlerDeploymentUtil.Listener listener)
        {
            this.contextHandlerCollection = contextHandlerCollection;
            this.listener = listener;
        }

        @Override
        public void beanAdded(Container parent, Object child)
        {
            if (parent == contextHandlerCollection)
            {
                if (child instanceof ContextHandler contextHandler)
                    listener.onAfterDeploy(contextHandler);
            }
        }

        @Override
        public void beanRemoved(Container parent, Object child)
        {
            if (parent == contextHandlerCollection)
            {
                if (child instanceof ContextHandler contextHandler)
                    listener.onUndeploy(contextHandler);
            }
        }

        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
            if (event instanceof ContextHandler contextHandler)
                listener.onFailedStart(contextHandler, cause);
        }

        @Override
        public void lifeCycleStarted(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
                listener.onAfterStart(contextHandler);
        }

        @Override
        public void lifeCycleStarting(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
                listener.onBeforeStart(contextHandler);
        }

        @Override
        public void lifeCycleStopped(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
                listener.onAfterStop(contextHandler);
        }

        @Override
        public void lifeCycleStopping(LifeCycle event)
        {
            if (event instanceof ContextHandler contextHandler)
                listener.onBeforeStop(contextHandler);
        }
    }
}
