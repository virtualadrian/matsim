/* *********************************************************************** *
 * project: org.matsim.*
 * TranitRouter.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2009 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.av.intermodal.router;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.GenericRouteImpl;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.Time;
import org.matsim.facilities.Facility;
import org.matsim.pt.router.PreparedTransitSchedule;
import org.matsim.pt.router.TransitLeastCostPathTree;
import org.matsim.pt.router.TransitLeastCostPathTree.InitialNode;
import org.matsim.pt.router.TransitRouter;
import org.matsim.pt.router.TransitRouterConfig;
import org.matsim.pt.router.TransitRouterNetwork;
import org.matsim.pt.router.TransitRouterNetwork.TransitRouterNetworkLink;
import org.matsim.pt.router.TransitRouterNetwork.TransitRouterNetworkNode;
import org.matsim.pt.router.TransitTravelDisutility;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Not thread-safe because MultiNodeDijkstra is not. Does not expect the TransitSchedule to change once constructed! michaz '13
 *
 * @author jbischoff
 * @author gleich
 */
public class VariableAccessTransitRouterImpl implements TransitRouter {

	private final TransitRouterNetwork transitNetwork;
	private final Network network;
	private final TransitRouterConfig config;
	private final TransitTravelDisutility travelDisutility;
	private final TravelTime travelTime;
	private final VariableAccessEgressTravelDisutility variableAccessEgressTravelDisutility;
	private boolean variableSurchargeOn;
	private final Random rand = MatsimRandom.getRandom();
	private Logger log = Logger.getLogger(VariableAccessTransitRouterImpl.class);

	private final PreparedTransitSchedule preparedTransitSchedule;

	

	public VariableAccessTransitRouterImpl(
			final TransitRouterConfig config,
			final PreparedTransitSchedule preparedTransitSchedule,
			final TransitRouterNetwork routerNetwork,
			final TravelTime travelTime,
			final TransitTravelDisutility travelDisutility, final VariableAccessEgressTravelDisutility variableAccessEgressTravelDisutility,
			final Network network) {
		log.info("VariableAccessTransitRouterImpl started");
		this.config = config;
		this.transitNetwork = routerNetwork;
		this.travelTime = travelTime;
		this.travelDisutility = travelDisutility;
		this.preparedTransitSchedule = preparedTransitSchedule;
		this.variableAccessEgressTravelDisutility = variableAccessEgressTravelDisutility;
		this.network = network;
		/* Before each iteration a new VariableAccessTransitRouterImpl is instantiated for each thread.
		 * Therefore any information whether an agent was routed last time with variableSurcharge or without
		 * is lost. As a temporary solution, let at each instantiation choose by random, if the
		 * variableSurcharge is applied (to all agents routed by this instance) or not.
		 */
		this.variableSurchargeOn = rand.nextBoolean();
	}

	// find the nearest pt stops in order to calculate the shortest path
	private Map<Node, InitialNode> locateWrappedNearestTransitNodes(Person person, Coord coord, double departureTime) {
		Collection<TransitRouterNetworkNode> nearestNodes = this.transitNetwork.getNearestNodes(coord, this.config.getSearchRadius());
		if (nearestNodes.size() < 2) {
			// also enlarge search area if only one stop found, maybe a second one is near the border of the search area
			TransitRouterNetworkNode nearestNode = this.transitNetwork.getNearestNode(coord);
			double distance = CoordUtils.calcEuclideanDistance(coord, nearestNode.stop.getStopFacility().getCoord());
			nearestNodes = this.transitNetwork.getNearestNodes(coord, distance + this.config.getExtensionRadius());
		}
		Map<Node, InitialNode> wrappedNearestNodes = new LinkedHashMap<>();
		/*
		 * Travel time surcharge to discouraged transit stops is added in every other routing per agent. This way all discouraged
		 * pt stops receive the travel time surcharge at the same time and one discouraged pt stop is not simply replaced by
		 * another discouraged pt stop. When the same agent is routed the next time, no surcharge will be added to any of the
		 * randomOnOff discouraged pt stop. See also DistanceBasedVariableAccessModule
		 */
		for (TransitRouterNetworkNode node : nearestNodes) {
			Coord toCoord = node.stop.getStopFacility().getCoord();
			Leg initialLeg = getAccessEgressLeg(person, coord, toCoord, departureTime, variableSurchargeOn);
			double initialTime = initialLeg.getTravelTime();
			double initialCost;
			if (initialLeg.getMode().equals(TransportMode.transit_walk) || initialLeg.getMode().equals(TransportMode.walk) ||
					initialLeg.getMode().equals(TransportMode.access_walk) || initialLeg.getMode().equals(TransportMode.egress_walk)) {
				//  getMarginalUtilityOfTravelTimeWalk INCLUDES the opportunity cost of time.  kai, dec'12
				double timeCost = - initialTime * config.getMarginalUtilityOfTravelTimeWalk_utl_s() ;
				// (sign: margUtl is negative; overall it should be positive because it is a cost.)
				double distanceCost = - initialLeg.getRoute().getDistance() * config.getMarginalUtilityOfTravelDistancePt_utl_m() ;
				// (sign: same as above)
				initialCost = timeCost + distanceCost;
			} else if (initialLeg.getMode().equals("drt")) {
				// this.marginalUtilityOfTravelTimeWalk_utl_s = pcsConfig.getModes().get(TransportMode.walk).getMarginalUtilityOfTraveling() /3600.0 - pcsConfig.getPerforming_utils_hr()/3600. ;
				double marginalUtilityOfTravelTimeDrt_utl_s = -4.0 / 3600 - 6.0 / 3600;
				double marginalUtilityOfTravelDistanceDrt_utl_m = 0;
				double timeCost = - initialTime * marginalUtilityOfTravelTimeDrt_utl_s;
				// (sign: margUtl is negative; overall it should be positive because it is a cost.)
				double distanceCost = - initialLeg.getRoute().getDistance() * marginalUtilityOfTravelDistanceDrt_utl_m ;
				// (sign: same as above)
				initialCost = timeCost + distanceCost;
			} else {
				throw new RuntimeException("Unable to provide initialCost for mode " + initialLeg.getMode() + " for which no marginal utility parameters are set in TransitRouterConfigGroup");
			}
			wrappedNearestNodes.put(node, new InitialNode(initialCost, initialTime + departureTime));
		}
		return wrappedNearestNodes;
	}

	private Leg getAccessEgressLeg(Person person, Coord coord, Coord toCoord, double time, boolean variableSurchargeOn) {
		return variableAccessEgressTravelDisutility.getAccessEgressModeAndTraveltime(person, coord, toCoord, time, variableSurchargeOn);
	}


	private double getTransferTime(Person person, Coord coord, Coord toCoord) {
		return travelDisutility.getTravelTime(person, coord, toCoord) + this.config.getAdditionalTransferTime();
	}

	private double getAccessEgressDisutility(Person person, Coord coord, Coord toCoord) {
		return travelDisutility.getTravelDisutility(person, coord, toCoord);
	}

	@Override
	public List<Leg> calcRoute(final Facility<?> fromFacility, final Facility<?> toFacility, final double departureTime, final Person person) {
		// find possible start stops
		Map<Node, InitialNode> wrappedFromNodes = this.locateWrappedNearestTransitNodes(person, fromFacility.getCoord(), departureTime);
		// find possible end stops
		Map<Node, InitialNode> wrappedToNodes = this.locateWrappedNearestTransitNodes(person, toFacility.getCoord(), departureTime);

		TransitLeastCostPathTree tree = new TransitLeastCostPathTree(transitNetwork, travelDisutility, travelTime,
				wrappedFromNodes, wrappedToNodes, person);

		// find routes between start and end stop
		Path p = tree.getPath(wrappedToNodes);

		if (p == null) {
			return null;
		}

		double directWalkCost = getAccessEgressDisutility(person, fromFacility.getCoord(), toFacility.getCoord());
		double pathCost = p.travelCost + wrappedFromNodes.get(p.nodes.get(0)).initialCost + wrappedToNodes.get(p.nodes.get(p.nodes.size() - 1)).initialCost;

		if (directWalkCost * config.getDirectWalkFactor() < pathCost) {
			return this.createDirectAccessEgressModeLegList(null, fromFacility.getCoord(), toFacility.getCoord(), departureTime);
		}
		return convertPathToLegList(departureTime, p, fromFacility.getCoord(), toFacility.getCoord(), person);
	}

	private List<Leg> createDirectAccessEgressModeLegList(Person person, Coord fromCoord, Coord toCoord, double time) {
		List<Leg> legs = new ArrayList<>();
		Leg leg = getAccessEgressLeg(person, fromCoord, toCoord, time, false);
		legs.add(leg);
		return legs;
	}

	// convert the shortest path found before to legs
	protected List<Leg> convertPathToLegList(double departureTime, Path path, Coord fromCoord, Coord toCoord, Person person) {
		// yy would be nice if the following could be documented a bit better.  kai, jul'16
		
		// now convert the path back into a series of legs with correct routes
		double time = departureTime;
		List<Leg> legs = new ArrayList<>();
		Leg leg;
		TransitLine line = null;
		TransitRoute route = null;
		TransitStopFacility accessStop = null;
		TransitRouteStop transitRouteStart = null;
		TransitRouterNetworkLink prevLink = null;
		double currentDistance = 0;
		int transitLegCnt = 0;
		for (Link ll : path.links) {
			TransitRouterNetworkLink link = (TransitRouterNetworkLink) ll;
			if (link.getLine() == null) {
				// (it must be one of the "transfer" links.) finish the pt leg, if there was one before...
				TransitStopFacility egressStop = link.fromNode.stop.getStopFacility();
				if (route != null) {
					leg = PopulationUtils.createLeg(TransportMode.pt);
					ExperimentalTransitRoute ptRoute = new ExperimentalTransitRoute(accessStop, line, route, egressStop);
					double arrivalOffset = (link.getFromNode().stop.getArrivalOffset() != Time.UNDEFINED_TIME) ? link.fromNode.stop.getArrivalOffset() : link.fromNode.stop.getDepartureOffset();
					double arrivalTime = this.preparedTransitSchedule.getNextDepartureTime(route, transitRouteStart, time) + (arrivalOffset - transitRouteStart.getDepartureOffset());
					ptRoute.setTravelTime(arrivalTime - time);

//					ptRoute.setDistance( currentDistance );
					ptRoute.setDistance( link.getLength() );
					// (see MATSIM-556)

					leg.setRoute(ptRoute);
					leg.setTravelTime(arrivalTime - time);
					time = arrivalTime;
					legs.add(leg);
					transitLegCnt++;
					accessStop = egressStop;
				}
				line = null;
				route = null;
				transitRouteStart = null;
				currentDistance = link.getLength();
			} else {
				// (a real pt link)
				currentDistance += link.getLength();
				if (link.getRoute() != route) {
					// the line changed
					TransitStopFacility egressStop = link.fromNode.stop.getStopFacility();
					if (route == null) {
						// previously, the agent was on a transfer, add the walk leg
						transitRouteStart = ((TransitRouterNetworkLink) ll).getFromNode().stop;
						if (accessStop != egressStop) {
							if (accessStop != null) {
								leg = PopulationUtils.createLeg(TransportMode.transit_walk);
								//							    double walkTime = getWalkTime(person, accessStop.getCoord(), egressStop.getCoord());
								double transferTime = getTransferTime(person, accessStop.getCoord(), egressStop.getCoord());
								Route walkRoute = new GenericRouteImpl(accessStop.getLinkId(), egressStop.getLinkId());
								// (yy I would have expected this from egressStop to accessStop. kai, jul'16)
								
								//							    walkRoute.setTravelTime(walkTime);
								walkRoute.setTravelTime(transferTime);
								
//								walkRoute.setDistance( currentDistance );
								walkRoute.setDistance( config.getBeelineDistanceFactor() * 
										NetworkUtils.getEuclideanDistance(accessStop.getCoord(), egressStop.getCoord()) );
								// (see MATSIM-556)

								leg.setRoute(walkRoute);
								//							    leg.setTravelTime(walkTime);
								leg.setTravelTime(transferTime);
								//							    time += walkTime;
								time += transferTime;
								legs.add(leg);
							} else {
								// accessStop == null, so it must be the first access-leg. If mode is e.g. taxi, we need a transit_walk to get to pt link
								leg = getAccessEgressLeg(person, fromCoord, egressStop.getCoord(), time, false);
								if (variableAccessEgressTravelDisutility.isTeleportedAccessEgressMode(leg.getMode()))
								{
									leg.getRoute().setEndLinkId(egressStop.getLinkId());
									time += leg.getTravelTime();
									legs.add(leg);


								} else {
									legs.add(leg); //access leg
									time += leg.getTravelTime();
									
									Route walkRoute = new GenericRouteImpl(leg.getRoute().getEndLinkId(), egressStop.getLinkId());
									double walkTime = getTransferTime(person, network.getLinks().get(leg.getRoute().getEndLinkId()).getToNode().getCoord(), egressStop.getCoord());
									walkRoute.setTravelTime(walkTime);
									walkRoute.setDistance(config.getBeelineDistanceFactor() * 
											NetworkUtils.getEuclideanDistance(network.getLinks().get(leg.getRoute().getEndLinkId()).getToNode().getCoord(), egressStop.getCoord()) );
								
									Leg walkleg = PopulationUtils.createLeg(TransportMode.transit_walk);
									walkleg.setTravelTime(walkTime);
									walkleg.setRoute(walkRoute);
									time += walkTime;
									legs.add(walkleg);
									
								}
								
//								walkRoute.setDistance( currentDistance );
								// (see MATSIM-556)

							}
						}
						currentDistance = 0;
					}
					line = link.getLine();
					route = link.getRoute();
					accessStop = egressStop;
				}
			}
			prevLink = link;
		}
		if (route != null) {
			// the last part of the path was with a transit route, so add the pt-leg and final walk-leg
			leg = PopulationUtils.createLeg(TransportMode.pt);
			TransitStopFacility egressStop = prevLink.toNode.stop.getStopFacility();
			ExperimentalTransitRoute ptRoute = new ExperimentalTransitRoute(accessStop, line, route, egressStop);
//			ptRoute.setDistance( currentDistance );
			ptRoute.setDistance( config.getBeelineDistanceFactor() * NetworkUtils.getEuclideanDistance(accessStop.getCoord(), egressStop.getCoord() ) );
			// (see MATSIM-556)
			leg.setRoute(ptRoute);
			double arrivalOffset = ((prevLink).toNode.stop.getArrivalOffset() != Time.UNDEFINED_TIME) ?
					(prevLink).toNode.stop.getArrivalOffset()
					: (prevLink).toNode.stop.getDepartureOffset();
					double arrivalTime = this.preparedTransitSchedule.getNextDepartureTime(route, transitRouteStart, time) + (arrivalOffset - transitRouteStart.getDepartureOffset());
					leg.setTravelTime(arrivalTime - time);
					ptRoute.setTravelTime( arrivalTime - time );
					legs.add(leg);
					transitLegCnt++;
					accessStop = egressStop;
		}
		if (prevLink != null) {
			if (accessStop == null) {
				// no use of pt
				leg = getAccessEgressLeg(person, fromCoord, toCoord, time, false);
				legs.add(leg);

			} else {
				Leg eleg = getAccessEgressLeg(person, accessStop.getCoord(), toCoord, time, false);
				
				if (variableAccessEgressTravelDisutility.isTeleportedAccessEgressMode(eleg.getMode())){
					leg = eleg;
					leg.getRoute().setStartLinkId(accessStop.getLinkId());
					legs.add(leg);

				}
				else {
					leg = PopulationUtils.createLeg(TransportMode.transit_walk);
					double walkTime = getTransferTime(person, accessStop.getCoord(), network.getLinks().get(eleg.getRoute().getStartLinkId()).getToNode().getCoord());
					leg.setTravelTime(walkTime);
					Route walkRoute = new GenericRouteImpl(accessStop.getLinkId(), eleg.getRoute().getStartLinkId());
					walkRoute.setDistance(config.getBeelineDistanceFactor() * 
											NetworkUtils.getEuclideanDistance(accessStop.getCoord(),network.getLinks().get(eleg.getRoute().getStartLinkId()).getToNode().getCoord()));
					leg.setRoute(walkRoute);
					legs.add(leg);
					legs.add(eleg);
				}
			}
		}
		if (transitLegCnt == 0) {
			// it seems, the agent only walked
			legs.clear();
			try{
			leg = getAccessEgressLeg(person, fromCoord, toCoord, time, false);

			legs.add(leg);
			} catch (NullPointerException e){
				throw new RuntimeException(" npe: person"+ person + fromCoord + toCoord + time);
			}
		}
		return legs;
	}

	public TransitRouterNetwork getTransitRouterNetwork() {
		return this.transitNetwork;
	}

	protected TransitRouterNetwork getTransitNetwork() {
		return transitNetwork;
	}

	protected TransitRouterConfig getConfig() {
		return config;
	}

}
