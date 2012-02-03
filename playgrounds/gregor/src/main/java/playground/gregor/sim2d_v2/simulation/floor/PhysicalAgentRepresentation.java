package playground.gregor.sim2d_v2.simulation.floor;

import com.vividsolutions.jts.geom.Coordinate;

public abstract class PhysicalAgentRepresentation {

	public static final double AGENT_WEIGHT = 80;
	public static final double AGENT_DIAMETER = 0.35;
	protected final double maxV = 2.;

	public PhysicalAgentRepresentation() {
		super();
	}

	abstract void update(double v, double alpha, Coordinate pos);
	
	public double getAgentDiameter() {
		return AGENT_DIAMETER;
	}

}