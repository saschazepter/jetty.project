# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo Jetty WebSocket Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy
ext
ee8-websocket-jetty

[files]
basehome:modules/demo.d/ee8-demo-jetty-websocket.xml|webapps/ee8-demo-jetty-websocket.xml
basehome:modules/demo.d/ee8-demo-jetty-websocket.properties|webapps/ee8-demo-jetty-websocket.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-jetty-websocket/webapp/${jetty.version}/war|webapps/ee8-demo-jetty-websocket.war