package org.rgrig;

import org.java_websocket.client.WebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;

public class App
{
    public static void main(final String[] args) throws URISyntaxException
    {
        // Submission Server
        // WebSocketClient client = new Client(new URI("ws://129.12.44.246:1234"), "dwa4", "597feceb");
        // Test Server (View Scores: http://129.12.44.229/)
        WebSocketClient client = new Client(new URI("ws://129.12.44.229:1234"), "dwa4", "597feceb");
        client.connect();
        // TODO: Add local test harness
    }
}
