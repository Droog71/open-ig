/*
 * Copyright 2008-2012, David Karnok 
 * The file is part of the Open Imperium Galactica project.
 * 
 * The code should be distributed under the LGPL license.
 * See http://www.gnu.org/licenses/lgpl.html for details.
 */

package hu.openig.mechanics;

import hu.openig.core.Action0;
import hu.openig.core.Location;
import hu.openig.model.AIBuilding;
import hu.openig.model.AIControls;
import hu.openig.model.AIFleet;
import hu.openig.model.AIInventoryItem;
import hu.openig.model.AIPlanet;
import hu.openig.model.AIWorld;
import hu.openig.model.BuildingType;
import hu.openig.model.EquipmentSlot;
import hu.openig.model.Fleet;
import hu.openig.model.Planet;
import hu.openig.model.ResearchSubCategory;
import hu.openig.model.ResearchType;
import hu.openig.utils.JavaUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The starmap discovery and satellite planner.
 * @author akarnokd, 2011.12.27.
 */
public class DiscoveryPlanner extends Planner {
	/** The current remaining exploration map. */
	final Set<Location> explorationMap;
	/** The cell size. */
	final int explorationCellSize;
	/**
	 * Constructor. Initializes the fields.
	 * @param world the world object
	 * @param controls the controls to affect the world in actions
	 */
	public DiscoveryPlanner(AIWorld world, AIControls controls) {
		super(world, controls);
		this.explorationMap = controls.explorationMap();
		this.explorationCellSize = controls.explorationCellSize();
	}
	@Override
	public void plan() {
		if (explorationMap.size() > 0) {
			// find a fleet which is not moving and has at least a decent radar range
			// and is among the fastest available
			AIFleet bestFleet = null;
			for (AIFleet f : world.ownFleets) {
				if (!f.isMoving() && f.radar >= w.params().fleetRadarUnitSize()) {
					if (bestFleet == null || bestFleet.statistics.speed < f.statistics.speed) {
						bestFleet = f;
					}
				}
			}
			
			if (bestFleet != null) {
				final AIFleet bf = bestFleet;
				final int ec = explorationCellSize;
				final Location loc = Collections.min(explorationMap, new Comparator<Location>() {
					@Override
					public int compare(Location o1, Location o2) {
						double d1 = Math.hypot(bf.x - (o1.x + 0.5) * ec, bf.y - (o1.y + 0.5) * ec);
						double d2 = Math.hypot(bf.x - (o2.x + 0.5) * ec, bf.y - (o2.y + 0.5) * ec);
						return d1 < d2 ? -1 : (d1 > d2 ? 1 : 0);
					}
				});
				add(new Action0() {
					@Override
					public void invoke() {
						controls.actionMoveFleet(bf.fleet, (loc.x + 0.5) * ec, (loc.y + 0.5) * ec);
					}
				});
				return;
			} else {
				planDiscoveryFleet();
			}
		}
		
		// traverse all known planet and deploy satellites
		outer:
		for (final AIPlanet planet : world.unknownPlanets) {
			AIInventoryItem currentSatellite = null;
			for (AIInventoryItem ii : planet.inventory) {
				if (ii.owner == p && ii.type.has("detector") 
						&& ii.type.category == ResearchSubCategory.SPACESHIPS_SATELLITES) {
					currentSatellite = ii;
				}
			}
			// find the most advanced satellite
			ResearchType sat = null;
			for (Map.Entry<ResearchType, Integer> e : world.inventory.entrySet()) {
				ResearchType rt = e.getKey();
				int radar = rt.getInt("detector", 0);
				if (e.getValue() > 0 
						&& rt.category == ResearchSubCategory.SPACESHIPS_SATELLITES
						&& radar > 0) {
					if (sat == null || sat.getInt("detector") < radar) {
						sat = rt;
					}
				}
			}
			if (sat != null) {
				// if we couldn't find a better satellite
				if (currentSatellite != null 
						&& currentSatellite.type.getInt("detector") >= sat.getInt("detector")) {
					continue outer;
				}
				final ResearchType sat0 = sat;
				final Planet planet0 = planet.planet;
				add(new Action0() {
					@Override
					public void invoke() {
						controls.actionDeploySatellite(planet0, sat0);
					}
				});
				return;
			} else {
				// find the best available detector
				for (ResearchType rt : p.available().keySet()) {
					if (rt.has("detector")) {
						if (sat == null || sat.getInt("detector") < rt.getInt("detector")) {
							sat = rt;
						}
					}
				}
				if (sat != null) {
					placeProductionOrder(sat, 10);
					return;
				}
			}
		}
	}
	/**
	 * Plan for discovery fleet creation.
	 */
	void planDiscoveryFleet() {
		if (checkMilitarySpaceport()) {
			return;
		}
		if (checkEquipment()) {
			return;
		}
		if (checkDeploy()) {
			return;
		}
	}
	/**
	 * Find the best available radar and ship in inventory, and deploy it.
	 * @return true if action taken
	 */
	boolean checkDeploy() {
		List<AIPlanet> mss = JavaUtils.newArrayList();
		for (final AIPlanet planet : world.ownPlanets) {
			for (final AIBuilding b : planet.buildings) {
				if (b.type.id.equals("MilitarySpaceport")) {
					if (!b.isDamaged() && b.isOperational()) {
						mss.add(planet);
						break;
					}
				}
			}
		}
		if (mss.isEmpty()) {
			return false;
		}
		final AIPlanet deploy = w.random(mss);
		ResearchType what = findBestFixed();
		if (what == null) {
			what = findBestNormal();
		}
		if (what != null) {
			final ResearchType fwhat = what;
			add(new Action0() {
				@Override
				public void invoke() {
					Fleet f = controls.actionCreateFleet(w.env.labels().get("discovery_fleet"), deploy.planet);
					f.addInventory(fwhat, 1);
					f.upgradeAll();
				}
			});
			return true;
		}
		return false;
	}
	/**
	 * Find the best regular ship which can be equipped with the best radar.
	 * @return the ship choosen
	 */
	ResearchType findBestNormal() {
		ResearchType best = null;
		int bestRadar = 0;
		for (Map.Entry<ResearchType, Integer> e : world.inventory.entrySet()) {
			if (e.getValue() == 0) {
				continue;
			}
			ResearchType rt = e.getKey();
			if (rt.category != ResearchSubCategory.SPACESHIPS_CRUISERS) {
				continue;
			}
			for (EquipmentSlot es : rt.slots.values()) {
				for (ResearchType rt0 : es.items) {
					if (rt0.has("radar") && world.inventoryCount(rt0) > 0) {
						if (bestRadar < rt0.getInt("radar")) {
							bestRadar = rt0.getInt("radar");
							best = rt;
						}
					}
				}
			}
		}
		return best;
	}
	/**
	 * Find the best available, fixed radar ship from the inventory.
	 * @return the best found
	 */
	ResearchType findBestFixed() {
		// check if we have a ship which has a fixed radar in inventory
		ResearchType bestFixed = null;
		int bestFixedRadar = 0;
		outer0:
		for (Map.Entry<ResearchType, Integer> e : world.inventory.entrySet()) {
			ResearchType rt = e.getKey();
			if (rt.category == ResearchSubCategory.SPACESHIPS_CRUISERS && e.getValue() > 0) {
				for (EquipmentSlot es : rt.slots.values()) {
					if (es.fixed) {
						for (ResearchType rt0 : es.items) {
							if (rt0.has("radar")) {
								if (bestFixed == null || bestFixedRadar < rt0.getInt("radar")) {
									bestFixed = rt;
									bestFixedRadar = rt0.getInt("radar");
								}
								continue outer0;
							}
						}
					}
				}
			}
		}
		return bestFixed;
	}
	/**
	 * Check if we have radar in inventory.
	 * @return if action taken
	 */
	boolean checkEquipment() {
		ResearchType bestFixed = findBestFixed();
		if (bestFixed != null) {
			return false;
		}
		// check if we can construct a ship with a fixed radar
		bestFixed = null;
		int bestFixedRadar = 0;
		outer1:
		for (ResearchType rt : world.availableResearch) {
			if (rt.category == ResearchSubCategory.SPACESHIPS_CRUISERS) {
				for (EquipmentSlot es : rt.slots.values()) {
					if (es.fixed) {
						for (ResearchType rt0 : es.items) {
							if (rt0.has("radar")) {
								if (bestFixed == null || bestFixedRadar < rt0.getInt("radar")) {
									bestFixed = rt;
									bestFixedRadar = rt0.getInt("radar");
								}
								continue outer1;
							}
						}
					}
				}
			}
		}
		if (bestFixed != null) {
			placeProductionOrder(bestFixed, 1);
			return true;
		}
		// find best available radar to produce
		ResearchType bestRadar = null;
		outer:
		for (ResearchType rt : world.availableResearch) {
			if (rt.has("radar") && rt.category == ResearchSubCategory.EQUIPMENT_RADARS) {
				// check the ships if there is any of the cruisers which can accept such radar
				for (ResearchType rt1 : world.availableResearch) {
					if (rt1.category == ResearchSubCategory.SPACESHIPS_CRUISERS) {
						for (EquipmentSlot es : rt1.slots.values()) {
							if (!es.fixed) {
								if (!es.items.contains(rt)) {
									continue outer;
								}
							}
						}
					}
				}
				if (bestRadar == null || bestRadar.getInt("radar") < rt.getInt("radar")) {
					bestRadar = rt;
				}
			}
		}
		if (bestRadar != null) {
			if (world.inventoryCount(bestRadar) > 0) {
				return checkShip(bestRadar);
			}
			placeProductionOrder(bestRadar, 5);
			return true;
		}
		return false;
	}
	/**
	 * Check if we have a ship we could deploy a radar to.
	 * @param radar the radar to apply
	 * @return true if action taken
	 */
	boolean checkShip(ResearchType radar) {
		ResearchType bestShip = null;
		for (Map.Entry<ResearchType, Integer> e : world.inventory.entrySet()) {
			if (e.getValue() == 0) {
				continue;
			}
			ResearchType rt = e.getKey();
			if (rt.category != ResearchSubCategory.SPACESHIPS_CRUISERS) {
				continue;
			}
			// check if supports the given radar
			for (EquipmentSlot es : rt.slots.values()) {
				if (es.items.contains(radar)) {
					// find the cheapest
					if (bestShip == null || bestShip.productionCost > rt.productionCost) {
						bestShip = rt;
						break;
					}
				}
			}
		}
		// there is one such
		if (bestShip != null) {
			return false;
		}
		// find the cheapest and produce it
		for (ResearchType rt : world.availableResearch) {
			if (rt.category != ResearchSubCategory.SPACESHIPS_CRUISERS) {
				continue;
			}
			// check if supports the given radar
			for (EquipmentSlot es : rt.slots.values()) {
				if (es.items.contains(radar)) {
					if (bestShip == null || bestShip.productionCost > rt.productionCost) {
						bestShip = rt;
						break;
					}
				}
			}
		}
		if (bestShip != null) {
			placeProductionOrder(bestShip, 1);
		}
		return true;
	}
	/**
	 * Create a military spaceport if necessary.
	 * @return true if action taken
	 */
	boolean checkMilitarySpaceport() {
		// if there is at least one operational we are done
		if (world.global.hasMilitarySpaceport) {
			return false;
		}
		// check if there is a spaceport which we could get operational
		for (final AIPlanet planet : world.ownPlanets) {
			for (final AIBuilding b : planet.buildings) {
				if (b.type.id.equals("MilitarySpaceport")) {
					if (b.isDamaged() && !b.repairing) {
						add(new Action0() {
							@Override
							public void invoke() {
								controls.actionRepairBuilding(planet.planet, b.building, true);
							}
						});
					}
					// found and wait for it to become available
					return true;
				}
			}
		}
		// build one somewhere
		final BuildingType ms = findBuilding("MilitarySpaceport");
		// check if we can afford it
		if (ms.cost <= world.money) {
			List<AIPlanet> planets = new ArrayList<AIPlanet>(world.ownPlanets);
			Collections.shuffle(planets);
			// try building one somewhere randomly
			for (final AIPlanet planet : planets) {
				if (planet.findLocation(ms) != null) {
					add(new Action0() {
						@Override
						public void invoke() {
							controls.actionPlaceBuilding(planet.planet, ms);
						}
					});
					return true;
				}
			}
			// there was no room, so demolish a trader's spaceport somewhere
			for (final AIPlanet planet : planets) {
				for (final AIBuilding b : planet.buildings) {
					if (b.type.id.equals("TradersSpaceport")) {
						add(new Action0() {
							@Override
							public void invoke() {
								controls.actionDemolishBuilding(planet.planet, b.building);
							}
						});
						return true;
					}
				}
			}			
		}
		// can't seem to do much now
		return true;
	}
}
