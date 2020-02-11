package org.rgrig;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.*;

class Client extends WebSocketClient
{
    State state = State.START;
    String goodCommit;
    String badCommit;
    List<String> queue;
    Map<String, Integer> rankings;
    Map<String, Set<String>> ancestors;
    String latestAskedCommit;
    String mostLikelySolution;
    String commitToAsk;
    boolean foundCommitToAsk;
    List<String> breadthFirst;
    Set<String> visitedInBreathFirst;
    Map<String, Set<String>> parents;
    Map<String, Boolean> answeredCommits;
    int interval;
    int threshold;
    int count;
    int parentsSize;
    int batch;
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
        System.out.println("received");
        switch (state) {
            case START:
                if (message.has("Problem")) {
                    JSONObject jsonProblem = message.getJSONObject("Problem");
                    goodCommit = jsonProblem.get("good").toString();
                    badCommit = jsonProblem.get("bad").toString();
                    JSONArray jsonDag = jsonProblem.getJSONArray("dag");
                    if (jsonDag.length() == 2) {
                        send(new JSONObject().put("Solution", badCommit).toString());
                    }
                    latestAskedCommit = null;
                    answeredCommits = new HashMap<>()
                    {{
                        put(goodCommit, true);
                        put(badCommit, false);
                    }};
                    mostLikelySolution = badCommit;
                    commitToAsk = null;
                    foundCommitToAsk = false;
                    genCommitsAndParentsMap(jsonDag);
                    trimTheFat();
                    parentsSize = parents.size();
                    threshold = 1000;
                    batch = 10;
                    interval = parentsSize / batch;
                    genBreadthAndRanking();
                    state = State.IN_PROGRESS;
                    if (foundCommitToAsk) {
                        foundCommitToAsk = false;
                        ask(commitToAsk);
                    } else {
                        ask(commitToQuestion());
                    }
                } else if (message.has("Score")) {
                    close();
                } else {
                    System.err.println("Unexpected message while waiting for a problem.");
                    close();
                }
                break;
            case IN_PROGRESS:
                if (message.has("Answer")) {
                    if (message.get("Answer").equals("Good")) {
                        answerGood();
                    } else if (message.get("Answer").equals("Bad")) {
                        answerBad();
                    }
                    if (rankings.isEmpty()) {
                        state = State.START;
                        send(new JSONObject().put("Solution", mostLikelySolution).toString());
                    } else {
                        if (foundCommitToAsk) {
                            foundCommitToAsk = false;
                            ask(commitToAsk);
                        } else {
                            ask(commitToQuestion());
                        }
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

    // Generate the commits array in order to generate the parents hashmap (key = commit, value = list of parents).
    private void genCommitsAndParentsMap(JSONArray jsonDag)
    {
        parents = new HashMap<>();
        for (int i = 0; i < jsonDag.length(); ++i) {
            JSONArray entry = jsonDag.getJSONArray(i);
            JSONArray iParents = entry.getJSONArray(1);
            Set<String> ps = new HashSet<>();
            for (int j = 0; j < iParents.length(); ++j) {
                ps.add(iParents.getString(j));
            }
            parents.put(entry.getString(0), ps);
        }
    }

    // Get rid of commits which aren't in the ancestry of the badCommit
    private void trimTheFat()
    {
        genBreadthFirstStack(badCommit);
        var newParents = new HashMap<String, Set<String>>();
        for (var commit : breadthFirst) {
            newParents.put(commit, parents.get(commit));
        }
        newParents.put(goodCommit, parents.get(goodCommit));
        parents = newParents;
    }

    // Generate list of commits from breadth first search, either all or some commits at certain intervals
    private void genBreadthAndRanking()
    {
        if (parentsSize > threshold) {
            genBreadthFirstStackAtInterval(badCommit);
        } else {
            genBreadthFirstStack(badCommit);
        }
        genRankings();
    }

    private void genBreadthFirstStackAtIntervalHelper(String commit)
    {
        for (String parent : parents.get(commit)) {
            if (!parent.equals(goodCommit) && parents.containsKey(parent)) {
                if (!visitedInBreathFirst.contains(parent)) {
                    queue.add(parent);
                    if (count % interval == 0) {
                        breadthFirst.add(parent);
                    }
                    visitedInBreathFirst.add(parent);
                    count++;
                }
            }
        }
    }

    private void genBreadthFirstStackAtInterval(String commit)
    {
        breadthFirst = new ArrayList<>();
        queue = new ArrayList<>();
        visitedInBreathFirst = new HashSet<>();
        count = 1;
        if (count % interval == 0) {
            breadthFirst.add(commit);
        }
        visitedInBreathFirst.add(commit);
        count++;
        genBreadthFirstStackAtIntervalHelper(commit);
        while (!queue.isEmpty() && (count <= parentsSize)) {
            String nextCommit = queue.get(0);
            queue.remove(0);
            genBreadthFirstStackAtIntervalHelper(nextCommit);
        }
    }

    private void genBreadthFirstStackHelper(String commit)
    {
        for (String parent : parents.get(commit)) {
            if (!parent.equals(goodCommit) && parents.containsKey(parent)) {
                if (!visitedInBreathFirst.contains(parent)) {
                    queue.add(parent);
                    breadthFirst.add(parent);
                    visitedInBreathFirst.add(parent);
                }
            }
        }
    }

    private void genBreadthFirstStack(String commit)
    {
        breadthFirst = new ArrayList<>();
        visitedInBreathFirst = new HashSet<>();
        queue = new ArrayList<>();
        breadthFirst.add(commit);
        visitedInBreathFirst.add(commit);
        genBreadthFirstStackHelper(commit);
        while (!queue.isEmpty()) {
            String nextCommit = queue.get(0);
            queue.remove(0);
            genBreadthFirstStackHelper(nextCommit);
        }
    }

    private void genRankingsHelper(String commit)
    {
        Set<String> tmpAncestry = new HashSet<>();
        for (String parent : parents.get(commit)) {
            if (ancestors.containsKey(parent)) {
                tmpAncestry.addAll(ancestors.get(parent));
            } else if (parents.containsKey(parent)) {
                breadthFirst = new ArrayList<>();
                visitedInBreathFirst = new HashSet<>();
                genBreadthFirstStack(commit);
                tmpAncestry = new HashSet<>(breadthFirst);
                break;
            }
            tmpAncestry.add(parent);
        }
        tmpAncestry.remove(goodCommit);
        ancestors.put(commit, tmpAncestry);
        if (!answeredCommits.containsKey(commit)) {
            int x = tmpAncestry.size() + 1;
            int n = parentsSize;
            int rank = Math.min(x, (n + 1) - x);
            int halfway = (n + 1) / 2;
            if (rank == halfway) {
                commitToAsk = commit;
                rankings.put(commit, rank);
                foundCommitToAsk = true;
            } else {
                rankings.put(commit, rank);
            }
        }
    }

    private void genRankings()
    {
        rankings = new HashMap<>();
        ancestors = new HashMap<>();
        List<String> finalBreadthFirst = new ArrayList<>(breadthFirst);
        for (int i = (finalBreadthFirst.size() - 1); i >= 0; i--) {
            genRankingsHelper(finalBreadthFirst.get(i));
            if (foundCommitToAsk) {
                break;
            }
        }
    }

    private String commitToQuestion()
    {
        int halfway = breadthFirst.size() / 2;
        Map.Entry<String, Integer> bestCommit = null;
        for (Map.Entry<String, Integer> entry : rankings.entrySet()) {
            if ((bestCommit == null || entry.getValue() > bestCommit.getValue())) {
                bestCommit = entry;
                if (bestCommit.getValue() == halfway) {
                    break;
                }
            }
        }
        assert bestCommit != null;
        return bestCommit.getKey();
    }

    private void ask(String commit)
    {
        latestAskedCommit = commit;
        send(new JSONObject().put("Question", commit).toString());
    }

    private void answerGood()
    {
        answeredCommits.put(latestAskedCommit, true);
        // Update parents after a good answer
        parents.remove(goodCommit);
        goodCommit = latestAskedCommit;
        for (String ancestor : ancestors.get(goodCommit)) {
            answeredCommits.put(ancestor, true);
            parents.remove(ancestor);
        }
        for (var entry : parents.entrySet()) {
            var newParents = new HashSet<String>();
            for (var parent : entry.getValue()) {
                if (parents.containsKey(parent)) {
                    newParents.add(parent);
                }
            }
            parents.put(entry.getKey(), newParents);
        }
        parentsSize = parents.size();
        interval = parentsSize / batch;
        genBreadthAndRanking();
    }

    private void answerBad()
    {
        answeredCommits.put(latestAskedCommit, false);
        badCommit = latestAskedCommit;
        mostLikelySolution = badCommit;
        // Update parents after a good answer
        var badCommitAncestry = new ArrayList<>(ancestors.get(badCommit))
        {{
            add(badCommit);
        }};
        var newParents = new HashMap<String, Set<String>>();
        for (var ancestor : badCommitAncestry) {
            newParents.put(ancestor, parents.get(ancestor));
        }
        parents = newParents;
        parentsSize = parents.size();
        interval = parentsSize / batch;
        genBreadthAndRanking();
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