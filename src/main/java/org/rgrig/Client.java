package org.rgrig;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Client extends WebSocketClient
{
    State state = State.START;
    Map<String, List<String>> parents;
    String[] commits;
    Map<String, Boolean> good;
    int next;
    String kentId;

    Client(final URI server, final String kentId)
    {
        super(server);
        this.kentId = kentId;
    }

    @Override
    public void onMessage(final String messageText)
    {
        final JSONObject message = new JSONObject(messageText);
        System.out.printf("received: %s\n", message);
        switch (state) {
            case START:
                if (message.has("Problem")) {
                    // Make an array with all commits, and prepare to remember if they are good or
                    // bad. Also, remember the problem dag.
                    JSONObject jsonProblem = message.getJSONObject("Problem");
                    JSONArray jsonDag = jsonProblem.getJSONArray("dag");
                    commits = new String[jsonDag.length()];
                    parents = new HashMap<>();
                    for (int i = 0; i < jsonDag.length(); ++i) {
                        JSONArray entry = jsonDag.getJSONArray(i);
                        commits[i] = entry.getString(0);
                        JSONArray iParents = entry.getJSONArray(1);
                        List<String> ps = new ArrayList<>();
                        for (int j = 0; j < iParents.length(); ++j) {
                            ps.add(iParents.getString(j));
                        }
                        parents.put(commits[i], ps);
                    }
                    good = new HashMap<>();
                    next = 0;
                    assert commits.length >= 2;
                    state = State.IN_PROGRESS;
                    ask();
                } else if (message.has("Score")) {
                    close();
                } else {
                    System.err.println("Unexpected message while waiting for a problem.");
                    close();
                }
                break;
            case IN_PROGRESS:
                if (message.has("Answer")) {
                    good.put(commits[next++], "Good".equals(message.get("Answer")));
                    if (next == commits.length) {
                        for (int j = 0; j < commits.length; ++j) {
                            boolean allParentsGood = true;
                            for (String p : parents.get(commits[j])) {
                                allParentsGood &= good.get(p);
                            }
                            if (!good.get(commits[j]) && allParentsGood) {
                                state = State.START;
                                send(new JSONObject().put("Solution", commits[j]).toString());
                                return;
                            }
                        }
                        assert false; // No BUG?
                    } else {
                        ask();
                    }
                } else {
                    System.err.println("Unexpected message while in-progress.");
                    close();
                }
                break;
            default:
                assert false;
        }
    }

    void ask()
    {
        send(new JSONObject().put("Question", commits[next]).toString());
    }

    @Override
    public void onClose(final int arg0, final String arg1, final boolean arg2)
    {
        System.out.printf("L: onClose(%d, %s, %b)\n", arg0, arg1, arg2);
    }

    @Override
    public void onError(final Exception arg0)
    {
        System.out.printf("L: onError(%s)\n", arg0);
    }

    @Override
    public void onOpen(final ServerHandshake hs)
    {
        send(new JSONObject().put("User", kentId).toString());
    }

    enum State
    {
        START, IN_PROGRESS,
    }
}