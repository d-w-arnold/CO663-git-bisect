package org.rgrig;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.*;

class Client extends WebSocketClient
{
    private final String kentId;
    private final String token;
    private State state = State.START;
    private String goodCommit;
    private String badCommit;
    private List<String> queue;
    private Map<String, Integer> rankings;
    private Map<String, Set<String>> ancestors;
    private String latestAskedCommit;
    private String mostLikelySolution;
    private String commitToAsk;
    private boolean foundCommitToAsk;
    private List<String> breadthFirst;
    private Set<String> visitedInBreathFirst;
    private Map<String, Set<String>> parents;
    private Map<String, Boolean> answeredCommits;
    private int interval;
    private int threshold;
    private int count;
    private int parentsSize;
    private int batch;
    private String repoName;
    private JSONArray jsonDag;
    private Map<String, Set<String>> parentsOriginal;
    private int instanceCounter;
    private int totalQuestionCounter;

    public Client(final URI server, final String kentId, final String token)
    {
        super(server);
        this.kentId = kentId;
        this.token = token;
        this.repoName = null;
        this.jsonDag = null;
        this.parentsOriginal = null;
        this.instanceCounter = 0;
        this.totalQuestionCounter = 0;
    }

    @Override
    public void onMessage(final String messageText)
    {
        final JSONObject message = new JSONObject(messageText);
        switch (state) {
            case START:
                startState(message);
                break;
            case IN_PROGRESS:
                inProgressState(message);
                break;
            default:
                assert false;
        }
    }

    /**
     * Start state process.
     *
     * @param message The message received as a JSON object.
     */
    private void startState(final JSONObject message)
    {
        if (message.has("Repo")) {
            startStateRepo(message);
        } else if (message.has("Instance")) {
            startStateInstance(message);
        } else if (message.has("Score")) {
            close();
        } else {
            System.err.println("Unexpected message while waiting for a problem.");
            close();
        }
    }

    /**
     * Start state process, when the message contains a repo.
     *
     * @param message The message received as a JSON object.
     */
    private void startStateRepo(final JSONObject message)
    {
        JSONObject jsonRepo = message.getJSONObject("Repo");
        repoName = jsonRepo.getString("name");
        jsonDag = jsonRepo.getJSONArray("dag");
        genCommitsAndParentsMap(jsonDag);
        instanceCounter = 0;
    }

    /**
     * Start state process, when the message contains an instance.
     *
     * @param message The message received as a JSON object.
     */
    private void startStateInstance(final JSONObject message)
    {
        if (repoName == null) {
            System.err.println("Protocol error: instance without having seen a repo.");
            close();
        }
        instanceCounter++;
        parents = parentsOriginal;
        JSONObject jsonInstance = message.getJSONObject("Instance");
        goodCommit = jsonInstance.getString("good");
        badCommit = jsonInstance.getString("bad");
        System.out.printf("Solving instance (good %s; bad %s) of %s (%d) (Total Questions Asked: %d)\n", goodCommit, badCommit, repoName, instanceCounter, totalQuestionCounter);
        latestAskedCommit = null;
        answeredCommits = new HashMap<>()
        {{
            put(goodCommit, true);
            put(badCommit, false);
        }};
        mostLikelySolution = badCommit;
        commitToAsk = null;
        foundCommitToAsk = false;
        trimTheFat();
        parentsSize = parents.size();
        threshold = 10000;
        batch = 100;
        interval = parentsSize / batch;
        genBreadthAndRanking();
        state = State.IN_PROGRESS;
        questionOrSolution();
    }


    /**
     * In-progress state process.
     *
     * @param message The message received as a JSON object.
     */
    private void inProgressState(final JSONObject message)
    {
        if (message.has("Answer")) {
            if (message.get("Answer").equals("Good")) {
                answerGood();
            } else if (message.get("Answer").equals("Bad")) {
                answerBad();
            }
            questionOrSolution();
        } else {
            System.err.println("Unexpected message while in-progress.");
            close();
        }
    }

    /**
     * Generate the commits array in order to generate the parents hashmap (key = commit, value = list of parents).
     *
     * @param jsonDag The JSON array containing relationships between commits.
     */
    private void genCommitsAndParentsMap(JSONArray jsonDag)
    {
        parentsOriginal = new HashMap<>();
        for (int i = 0; i < jsonDag.length(); ++i) {
            JSONArray entry = jsonDag.getJSONArray(i);
            JSONArray iParents = entry.getJSONArray(1);
            Set<String> ps = new HashSet<>();
            for (int j = 0; j < iParents.length(); ++j) {
                ps.add(iParents.getString(j));
            }
            parentsOriginal.put(entry.getString(0), ps);
        }
    }

    /**
     * Get rid of commits which aren't in the ancestry of the badCommit.
     */
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

    /**
     * Generate list of commits from breadth first search, either all or some commits at certain intervals.
     */
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
                    if (count % interval == 1) {
                        breadthFirst.add(parent);
                    }
                    visitedInBreathFirst.add(parent);
                    count++;
                }
            }
        }
    }

    /**
     * Generate breath first (search) stack.
     *
     * @param commit The commit to start the search.
     */
    private void genBreadthFirstStackAtInterval(String commit)
    {
        breadthFirst = new ArrayList<>();
        queue = new ArrayList<>();
        visitedInBreathFirst = new HashSet<>();
        count = 1;
        breadthFirst.add(commit);
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

    /**
     * Generate breath first (search) stack.
     */
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

    /**
     * Generate scores for all commits in breadth first stack, which aren't already known to be good/bad.
     */
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

    /**
     * Decide whether to ask a question or send a solution.
     */
    private void questionOrSolution()
    {
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
    }

    //

    /**
     * We haven't found optimal commit to ask, so find commit with best score/ranking to question.
     *
     * @return The commit to query/question next.
     */
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
        totalQuestionCounter++;
        send(new JSONObject().put("Question", commit).toString());
    }

    /**
     * Good answer received.
     */
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

    /**
     * Bad answer received.
     */
    private void answerBad()
    {
        answeredCommits.put(latestAskedCommit, false);
        badCommit = latestAskedCommit;
        mostLikelySolution = badCommit;
        // Update parents after a bad answer
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
        arg0.printStackTrace();
    }

    @Override
    public void onOpen(final ServerHandshake hs)
    {
        JSONArray authorization = new JSONArray(new Object[]{kentId, token});
        send(new JSONObject().put("User", authorization).toString());
        setConnectionLostTimeout(0);
    }

    enum State
    {
        START, IN_PROGRESS,
    }
}
