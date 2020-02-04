package org.rgrig;

import org.java_websocket.client.WebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;

public class App
{
    public static void main(final String[] args) throws URISyntaxException
    {
//        if (args.length != 2) {
//            System.err.println("usage: <cmd> <server-ip> <kent-id>");
//            return;
//        }
//        String uri = String.format("ws://%s:1234", args[0]);
//        String id = args[1];
        WebSocketClient client = new Client(new URI("ws://129.12.44.229:1234"), "dwa4"); // http://129.12.44.229/
        client.connect();
    }
}
