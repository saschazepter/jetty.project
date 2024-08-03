# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Enables an un-assembled Maven webapp to run in a Jetty distribution.

[environment]
ee11

[depends]
server
ee11-webapp
ee11-annotations

[lib]
lib/ee11-maven/*.jar

[xml]
etc/jetty-ee11-maven.xml