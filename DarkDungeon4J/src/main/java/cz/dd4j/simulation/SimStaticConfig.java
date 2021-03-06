package cz.dd4j.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import cz.cuni.amis.utils.eh4j.shortcut.EH;
import cz.dd4j.agents.IHeroAgent;
import cz.dd4j.domain.DD4JDomainInit;
import cz.dd4j.domain.EEntity;
import cz.dd4j.simulation.actions.EAction;
import cz.dd4j.simulation.actions.instant.IInstantAction;
import cz.dd4j.simulation.data.agents.AgentMindBody;
import cz.dd4j.simulation.data.agents.Agents;
import cz.dd4j.simulation.data.dungeon.elements.entities.Hero;
import cz.dd4j.simulation.data.state.SimState;
import cz.dd4j.utils.csv.CSV.CSVRow;
import cz.dd4j.utils.reporting.IReporting;

public class SimStaticConfig implements IReporting {
	
	static {
		DD4JDomainInit.init();
	}
	
	// ===========
	// USER CONFIG
	// ===========
	
	public String id;
	public String description;

	/**
	 * Whether to collect executed actions within {@link SimStaticStats#actionSelectedStats} and {@link SimStaticStats#actionExecutedStats}.
	 */
	public boolean collectActionStats = true; 
	
	/**
	 * Alters the timeout for the number of steps. Once an agent executes PARAM*dungeon.#rooms step without reaching the goal, the simulation will timeout.
	 */
	public double timeoutMultiplier = 10;	
	
	// ============
	// CONFIG STATE
	// ============
	
	private boolean simStateBound = false;
	private boolean[] entityActionsBound = new boolean[EEntity.LAST_ID+1];	
	private boolean heroesBound;
	
	// ====================
	// ACTION CONFIGURATION
	// ====================
	
	/**
	 * Master random - used for generating all other random generators.
	 */
	public Random random = new Random(1);
	
	// DO NOT MODIFY FROM THE OUTSIDE!
	// Always use bind...() methods!
	
	/**
	 * [EEntity.id][EAction.id]
	 */
	public IInstantAction[][] actionExecutors;
	
	// =========
	// SIM STATE
	// =========
	
	// TODO: this should not be used by {@link SimStatic} directly but clone()d...
	public SimState state;
	
	public SimStaticConfig() {
		for (int i = 0; i < entityActionsBound.length; ++i) {
			entityActionsBound[i] = false;
		}
	}
	
	public boolean isReady() {
		return simStateBound && isEntitiesActionsBound() && heroesBound;
	}
	
	public boolean isEntitiesActionsBound() {
		if (actionExecutors == null) return false;
		for (EEntity entity : EH.enums(EEntity.class)) {
			if (!isEntityActionsBound(entity)) return false;
		}
		return true;
	}
	
	public boolean isEntityActionsBound(EEntity entity) {
		if (actionExecutors == null) return false;
		return entityActionsBound[entity.entityId];
	}

	public String getMissingInitDescription() {
		String desc = "";
		
		desc += (simStateBound ? "" : "SimState not bound! Call bindSimState(...) first. ");
		desc += (isEntityActionsBound(EEntity.HERO) ? "" : "Hero action executors not bound! Call bindEntityActions(...) for EEntity.HERO first. ");
		desc += (isEntityActionsBound(EEntity.MONSTER) ? "" : "Monster action executors not bound! Call bindEntityActions(...) for EEntity.MONSTER first. ");
		desc += (isEntityActionsBound(EEntity.FEATURE) ? "" : "Feature action executors not bound! Call bindEntityActions(...) for EEntity.FEATURE first. ");
		desc += (heroesBound ? "" : "Hero agents not bound! Call bindHeroes(...) first. ");
		
		if (desc.length() == 0) return "ALL OK!";
		
		return desc;
	}
	
	// =======================
	// ONE-TIME INITIALIZATION
	// =======================
	
	public void bindSimState(SimState state) {
		if (simStateBound) throw new RuntimeException("You cannot bindSimState() twice!");
		
		this.state = state;		
		this.state.config = this;
		
		simStateBound = true;
	}
	
	private void ensureActionExecutors() {
		if (actionExecutors == null) {
			this.actionExecutors = new IInstantAction[EEntity.LAST_ID+1][EAction.LAST_ID+1];
		}
	}
	
	public void bindActions(EEntity type, IInstantAction... actionExecutors) {
		if (entityActionsBound[type.entityId]) throw new RuntimeException("You cannot bindActions() for EEntity." + EH.getEnumObject(type).name + " twice!");
		
		ensureActionExecutors();
		
		for (IInstantAction executor : actionExecutors) {
			this.actionExecutors[type.entityId][executor.getType().id] = executor;
		}
		
		this.entityActionsBound[type.entityId] = true;
	}
	
	public void bindHeroes(Agents<IHeroAgent> heroes) {
		if (heroesBound) throw new RuntimeException("You cannot bindHeroes() twice!");
		
		for (AgentMindBody<Hero, IHeroAgent> hero : state.heroes.values()) {
			if (!heroes.agents.containsKey(hero.body.id)) {
				throw new RuntimeException("Cannot bind mind into hero body for Hero[id=" + hero.body.id + "], " + hero.body.id + " not found in 'heroes'.");
			}
			hero.mind = heroes.agents.get(hero.body.id);
		}
		
		heroesBound = true;		
	}
	
	// =========
	// REPORTING
	// =========

	public static final String CSV_ID = "SIM-id";
	public static final String CSV_DESC = "SIM-desc";
	
	@Override
	public List<String> getCSVHeaders() {
		List<String> result = new ArrayList<String>();
		result.add(CSV_ID);
		result.add(CSV_DESC);
		
		return result;
	}

	@Override
	public CSVRow getCSVRow() {
		CSVRow result = new CSVRow();
		
		result.add(CSV_ID, id);
		result.add(CSV_DESC, description);

		return result;
	}
	
}
