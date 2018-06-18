package cz.dd4j.agents.heroes.pddl;

import cz.dd4j.agents.commands.Command;
import cz.dd4j.domain.EFeature;
import cz.dd4j.domain.EItem;
import cz.dd4j.simulation.data.dungeon.elements.entities.Monster;
import cz.dd4j.simulation.data.dungeon.elements.items.Item;
import cz.dd4j.simulation.data.dungeon.elements.places.Corridor;
import cz.dd4j.simulation.data.dungeon.elements.places.Room;
import cz.dd4j.utils.astar.AStar;
import cz.dd4j.utils.astar.IAStarHeuristic;
import cz.dd4j.utils.astar.IAStarView;
import cz.dd4j.utils.astar.Path;
import cz.dd4j.utils.config.AutoConfig;
import cz.dd4j.utils.config.Configurable;
import cz.dd4j.utils.csv.CSV;

import java.util.*;
import java.util.logging.Level;

@AutoConfig
public class ForwardCheckingAgent01 extends PDDLAgentBase {

    @Configurable
    protected int dangerThreshold = 2;

    @Configurable
    protected int safeThreshold = 3;

    protected List<PDDLAction> currentPlan;
    private boolean reactiveActionTaken;
    private boolean reactiveEscape = false;
    private int safeSteps = 0;

    protected int reactiveActions = 0;

    protected Monster dangerousMonster = null;


    @Override
    public void prepareAgent() {
        super.prepareAgent();
    }

    protected boolean shouldReplan() {

        if (reactiveActionTaken)
            return true;

        if (currentPlan == null || currentPlan.isEmpty()) //no plan or plan finished
            return true;

        PDDLAction action = currentPlan.get(0);
        return !actionValidator.isValid(hero, translateAction(action));

    }

    private Command getBestReactiveAction() {
        reactiveActions++;
        reactiveActionTaken = true;
        List<Command> availableActions = actionsGenerator.generateFor(hero);

        System.out.println("Reactive action");

        Command selectedAction = null;
        int bestVal = Integer.MIN_VALUE;
        for (Command c: availableActions) {
            int val = evaluateCommand(c);
            if (val > bestVal) {
                selectedAction = c;
                bestVal = val;
            }
        }

        return selectedAction;
    }

    @Override
    public Command act() {

        int dng = dang(hero.atRoom);
        if (dng == 0) {
            return null;
        }

        if (shouldReplan()) {
            currentPlan = plan();
            if (currentPlan != null)
                safeSteps = evaluatePlan(currentPlan);
        }

        if (currentPlan == null) { //no plan found in previous step
            this.log(Level.INFO, "No plan found, taking reactive action");
            return getBestReactiveAction();
        }

        // recompute the safe steps, maybe we are lucky and the plan is actually OK
        if (safeSteps <= 0) {
            log(Level.INFO, String.format("Plan seems unsafe - can be disrupted after %d steps", safeSteps));
            safeSteps = evaluatePlan(currentPlan);
            if (safeSteps > dangerThreshold) {
                log(Level.INFO, (String.format("Got lucky, plan is actually safe for the next %d steps", safeSteps)));
            } else {
                log(Level.INFO, "Replanning to safety...");
                dangerousMonster = getClosestMonster(hero.atRoom);
                if (dangerousMonster != null) {
                    currentPlan = plan(String.format("(and (alive)(has_sword)(not(monster_at %s)))", dangerousMonster.atRoom.id.name));
                }
                if (dangerousMonster == null || currentPlan == null) { // no monster or planning failed
                    currentPlan = plan();
                }
                if (currentPlan != null) { // planning successful
                    safeSteps = evaluatePlan(currentPlan);
                    log(Level.INFO, String.format("New plan safe for %d steps", safeSteps));
                } else {
                    log(Level.INFO, "Planning failed");
                }
            }
        }

        // we are lucky
        if (currentPlan != null && safeSteps > 0) {
            log(Level.INFO, String.format("Following latest plan, first disruption possible after %d steps", safeSteps));
            safeSteps--;
            reactiveActionTaken = false;
            return translateAction(currentPlan.remove(0));
        }

        log(Level.INFO, "reactive escape: " + dang(hero.atRoom));

        return getBestReactiveAction();

    }

    private int evaluateCommand(Command cmd) {

        return dangAfterAction(cmd);
    }


    private int evaluatePlan(List<PDDLAction> plan) {

        final HashSet<String> removedTraps = new HashSet<String>();

        Item oldHand = this.hero.hand;
        boolean hasSword = this.hero.hand != null;
        Room location = hero.atRoom;

        int min_dang = dang(hero.atRoom);
        ArrayList<Integer> dangs = new ArrayList<Integer>();
        dangs.add(min_dang);

        for (int i = 0; i < plan.size(); i++) {

            PDDLAction action = plan.get(i);

            if (action.action.name().equals("MOVE") && action.arg2 != null) {
                location = dungeon.rooms.get(action.arg2);
            }

            if (action.action.name().equals("DROP")) {
                hasSword = false;
            }

            if (action.action.name().equals("PICKUP")) {
                this.hero.hand = new Item(EItem.SWORD);
                hasSword = true;
            }

            if (action.action.name().equals("DISARM") && action.arg2 != null) {
                removedTraps.add(location.name);

                int minD = Integer.MAX_VALUE;
                for (Corridor c: location.corridors) {
                    int d = getClosestMonsterDistance(c.getOther(location), removedTraps);
                    minD = Math.min(minD, d);
                }
                minD = Math.max(minD, 0); // the monster could not move through the trap
                dangs.add(minD + 1); // the monster needs one step to get to the room
            }

            if (hasSword) {
                dangs.add(Integer.MAX_VALUE);
                continue;
            }

            int dang = getClosestMonsterDistance(location, removedTraps);

            dangs.add(dang - i - 1);
        }

        System.err.println("current dang: " + dangs.get(0));
        for (int i = 0; i < currentPlan.size(); i++) {
            System.err.println("" + i + ": " + currentPlan.get(i).action.name() + ": " + dangs.get(i+1));
        }

        this.hero.hand = oldHand;

        int lastSafe = 0;
        for (int i = 0; i < dangs.size(); i++) {
            if (dangs.get(i) >= dangerThreshold) {
                lastSafe = i;
            }
            if (dangs.get(i) <= 0)
                    return lastSafe;
        }

        return Integer.MAX_VALUE;

    }

    private int getClosestMonsterDistance(Room r, final HashSet<String> removedTraps) {

        int minDist = Integer.MAX_VALUE;

        AStar<Room> astar = new AStar<Room>(new IAStarHeuristic<Room>() {
            @Override
            public int getEstimate(Room n1, Room n2) {
                return 0;
            }
        });

        for (Room room: dungeon.rooms.values()) {
            if (room.monster != null) {
                Path<Room> path = astar.findPath(room, r, new IAStarView() {
                    @Override
                    public boolean isOpened(Object o) {
                        Room r = (Room)o;
                        return r.feature == null || r.feature.type != EFeature.TRAP || removedTraps.contains(r.name);
                    }
                });
                if (path != null) {
                    minDist = Math.min(minDist, path.getDistanceNodes());
                }
            }
        }

        return minDist;
    }


    @Override
    public List<String> getCSVHeaders() {
        List<String> headers = super.getCSVHeaders();
        headers.add("reactive_steps");
        return headers;
    }

    @Override
    public CSV.CSVRow getCSVRow() {
        CSV.CSVRow row = super.getCSVRow();
        row.add("reactive_steps", reactiveActions);
        return row;
    }


}
