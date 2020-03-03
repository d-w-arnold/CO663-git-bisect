package org.rgrig;

import org.java_websocket.client.WebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;

public class App
{
    public static void main(final String[] args) throws URISyntaxException
    {
        WebSocketClient client = new Client(new URI("ws://129.12.44.229:1234"), "dwa4", "597feceb"); // http://129.12.44.229/
        client.connect();
    }
}
