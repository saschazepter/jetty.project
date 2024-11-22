# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Demo Jetty WebSocket Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy
ext
ee10-websocket-jetty

[files]
basehome:modules/demo.d/ee10-demo-jetty-websocket.xml|webapps/ee10-demo-jetty-websocket.xml
basehome:modules/demo.d/ee10-demo-jetty-websocket.properties|webapps/ee10-demo-jetty-websocket.properties
maven://org.eclipse.jetty.demos/jetty-ee10-demo-jetty-websocket/webapp/${jetty.version}/war|webapps/ee10-demo-jetty-websocket.war