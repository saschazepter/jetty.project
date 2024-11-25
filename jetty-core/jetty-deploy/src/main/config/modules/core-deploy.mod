[description]
Enables application based on core handlers deployed from the $JETTY_BASE/webapps/ directory.

[environment]
core

[depend]
deploy

[lib]

[files]
webapps/

[xml]
etc/jetty-core-deploy.xml

[ini-template]
## Default ContextHandler class for "core" environment deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.ResourceHandler$ResourceContext
