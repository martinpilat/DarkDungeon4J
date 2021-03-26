package cz.dd4j.agents.heroes;

import java.util.*;
import java.util.logging.Level;

import cz.dd4j.agents.HeroAgentBase;
import cz.dd4j.agents.IHeroAgent;
import cz.dd4j.agents.commands.Command;
import cz.dd4j.domain.EDungeonLabel;
import cz.dd4j.domain.EItem;
import cz.dd4j.domain.ELabel;
import cz.dd4j.simulation.actions.EAction;
import cz.dd4j.simulation.data.dungeon.Dungeon;
import cz.dd4j.simulation.data.dungeon.elements.places.Room;
import cz.dd4j.utils.astar.AStar;
import cz.dd4j.utils.astar.IAStarHeuristic;
import cz.dd4j.utils.astar.IAStarView;
import cz.dd4j.utils.astar.Path;

/**
 * Using rules for determining in what state it should enter the room.
 *
 * Move actions are chosen according to the closest way to the goal, picking up the sward is preferred
 * to move to goal.
 *
 * NEVER goes to the room, which contains unbeatable danger.
 *
 * @author Martin
 */
public class HeroRulesWithDFSMove extends HeroAgentBase implements IHeroAgent {

    private Command moveIntention;
    private Dungeon myDungeon;
    private Room goalRoom;
    private boolean needSword;
    private long monsterCount;
    private HashMap<String, Integer> visitedRooms;

    int distToClosestSword(Room from, List<Room> swordRooms) {

        int minDist = Integer.MAX_VALUE;
        for (Room r: swordRooms) {
            minDist = Math.min(minDist, roomDistance(from, r));
        }

        return minDist;
    }

    int roomDistance(Room from, Room to) {

        String topology = myDungeon.labels.getString(EDungeonLabel.TOPOLOGY_TYPE);
        int width = myDungeon.labels.getInt(EDungeonLabel.TOPOLOGY_ROOMS_WIDTH);
        int height = myDungeon.labels.getInt(EDungeonLabel.TOPOLOGY_ROOMS_HEIGHT);

        String fromIDStr = from.id.name;
        String toIDStr = to.id.name;

        int fromID = Integer.parseInt(fromIDStr.substring(4));
        int toID = Integer.parseInt(toIDStr.substring(4));

        int fromX = ( fromID  - 1) % width;
        int fromY = ( fromID  - 1) / width;

        int toX = ( toID  - 1) % width;
        int toY = ( toID  - 1) / width;

        if (topology.equals(EDungeonLabel.TOPOLOGY_TYPE_VALUE_GRID))
            return (fromX - toX) * (fromX - toX) + (fromY - toY) * (fromY - toY);

        int horDist = Math.min(Math.abs(fromX - toX), width - Math.abs(fromX - toX));
        int verDist = Math.min(Math.abs(fromY - toY), height - Math.abs(fromY - toY));

        return horDist*horDist + verDist+verDist;
    }

    @Override
    public Command act() {

        log(Level.INFO,"In this room for " + visitedRooms.getOrDefault(hero.atRoom.id.name, 0) + " time");

        if (hero.atRoom.monster != null && hero.hand != null && hero.hand.type == EItem.SWORD) return actions.attack();
        if (hero.atRoom.feature != null && hero.hand == null) return actions.disarm();
        if (moveIntention == null && hero.atRoom.item != null && hero.hand == null) return actions.pickup();

        needSword = monsterCount > 0 && (hero.hand == null || hero.hand.type != EItem.SWORD);
        final List<Room> swordRooms = new ArrayList<Room>();
        for (Room r: myDungeon.rooms.values()) {
            if (r.item != null) {
                swordRooms.add(r);
            }
        }

        // ALL POSSIBLE MOVE ACTIONS
        List<Command> moveActions = actionsGenerator.generateFor(hero, EAction.MOVE);

        if (needSword) {
            Collections.sort(moveActions, new Comparator<Command>() {
                @Override
                public int compare(Command o1, Command o2) {
                    int v1 = visitedRooms.getOrDefault(o1.target.id.name, 0);
                    int v2 = visitedRooms.getOrDefault(o2.target.id.name, 0);
                    if (v1 != v2)
                        return Integer.compare(v1, v2);
                    int d1 = distToClosestSword((Room) o1.target, swordRooms);
                    int d2 = distToClosestSword((Room) o2.target, swordRooms);
                    return Integer.compare(d1, d2);
                }
            });
        } else {
            Collections.sort(moveActions, new Comparator<Command>() {
                @Override
                public int compare(Command o1, Command o2) {
                    int v1 = visitedRooms.getOrDefault(o1.target.id.name, 0);
                    int v2 = visitedRooms.getOrDefault(o2.target.id.name, 0);
                    if (v1 != v2)
                        return Integer.compare(v1, v2);
                    int d1 = roomDistance((Room) o1.target, goalRoom);
                    int d2 = roomDistance((Room) o2.target, goalRoom);
                    return Integer.compare(d1, d2);
                }
            });
        }

        while (moveActions.size() > 0) {
            // NO MOVE INTENTION?
            if (moveIntention == null) {
                moveIntention = moveActions.remove(0);
            }

            // ASSESS MOVE INTENTION
            Room target = (Room)(moveIntention.target);

            // TRAP AT THE TARGET ROOM?
            if (target.feature != null) {
                // SOMETHING IN HANDS?
                if (hero.hand != null) {
                    // DROP FIRST
                    return actions.drop();
                }
            }

            // MONSTER AT THE TARGET ROOM?
            if (target.monster != null) {
                // AND NO SWORD?
                if (hero.hand == null) {
                    // SWORD IN THE ROOM?
                    if (hero.atRoom.item != null && hero.atRoom.item.isA(EItem.SWORD)) {
                        return actions.pickup();
                    } else {
                        // NO SWORD TO PICKUP
                        // => DO NOT GO
                        moveIntention = null;
                        // => TRY ANOTHER OPTION WHERE TO GO
                        continue;
                    }
                }
            }

            // ALL GOOD, PROCEED
            Command moveAction = moveIntention;
            moveIntention = null;
            String targetRoom = ((Room)moveAction.target).id.name;
            visitedRooms.put(targetRoom, visitedRooms.getOrDefault(targetRoom, 0) + 1);
            return moveAction;
        }

        // DUNNO WHAT TO DO...
        // => wait...
        return null;
    }

    @Override
    public void observeDungeon(Dungeon dungeon, boolean full, long timestampMillis) {
        myDungeon = dungeon;

        monsterCount = 0;
        for (Room r: dungeon.rooms.values()) {
            if (goalRoom == null) {
                if (r.isGoalRoom())
                    goalRoom = r;
            }
            if (r.monster != null) {
                monsterCount++;
            }

        }

        if (visitedRooms == null) {
            visitedRooms = new HashMap<>();
        }

    }


}
