package playground.christoph.network;

import java.util.Set;

import org.matsim.api.basic.v01.Coord;
import org.matsim.api.basic.v01.Id;
import org.matsim.api.basic.v01.TransportMode;
import org.matsim.api.basic.v01.network.BasicLink;
import org.matsim.api.basic.v01.network.BasicNode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.world.Layer;

//public class SubLink implements BasicLink, Link{
public class SubLink implements Link{

	private Network network;
	private Node from;
	private Node to;
	private Link parentLink;
	
	public SubLink(Network network, Node from, Node to, Link link)
	{
		this.network = network;
		this.from = from;
		this.to = to;
		this.parentLink = link;
	}
	
	public Link getParentLink()
	{
		return parentLink;
	}
	
	public Node getFromNode()
	{ 
		return this.from;
	}
	
	public Node getToNode()
	{
		return this.to;
	}
	
	public double getCapacity(final double time)
	{
		return parentLink.getCapacity(time);
	}

	public double getFreespeed(final double time)
	{
		return parentLink.getFreespeed(time);
	}

	public double getLength()
	{
		return parentLink.getLength();
	}

	public double getNumberOfLanes(final double time)
	{
		return parentLink.getNumberOfLanes(time);
	}

	public Set<TransportMode> getAllowedModes()
	{
		return parentLink.getAllowedModes();
	}

	public void setAllowedModes(Set<TransportMode> modes)
	{
		// nothing to do...
	}

	public void setCapacity(double capacity)
	{
		// nothing to do...
	}

	public void setFreespeed(double freespeed)
	{
		// nothing to do...
	}

	public boolean setFromNode(BasicNode node)
	{
		this.from = (Node)node;
		return true;
	}

	public void setLength(double length)
	{
		// nothing to do...	
	}

	public void setNumberOfLanes(double lanes)
	{
		// nothing to do...
	}

	public boolean setToNode(BasicNode node)
	{
		this.to = (Node)node;
		return true;
	}

	public Id getId()
	{
		return parentLink.getId();
	}

	public Layer getLayer()
	{
		return parentLink.getLayer();
	}

	public Coord getCoord()
	{
		return parentLink.getCoord();
	}
	
}