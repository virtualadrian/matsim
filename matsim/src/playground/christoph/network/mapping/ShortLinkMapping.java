package playground.christoph.network.mapping;

import org.matsim.api.basic.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;

/*
 * Mapping of a short Link. The Link is removed
 * and its Start- and EndNodes are mapped to a new
 * MappingNode.
 * 
 * In the Implementation we have a Link Array because
 * every pair of Nodes should have two Links in the
 * used Networks - one from A to B and one from B to A.
 * 
 * We assume that the Mapping Operation is only used,
 * if both Links have the same length. Otherwise
 * we would not be able to assign the correct mapping
 * length to the MappingNode. 
 */
public class ShortLinkMapping extends Mapping{

	private Link[] input;
	private Node output;
	
	public ShortLinkMapping(Id id, Link[] input, Node output)
	{
		this.setId(id);
		this.input = input;
		this.output = output;
	}
	
	public Link[] getInput()
	{
		return input;
	}
	
	public Node getOutput()
	{
		return output;
	}

	@Override
	public double getLength()
	{
		if (input[0] instanceof MappingInfo)
		{
			return ((MappingInfo) input[0]).getDownMapping().getLength();
		}
		else return input[0].getLength();
	}
}
