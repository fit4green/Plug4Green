/**
 * ============================== Header ============================== 
 * file:          ModelConstraintFactory.java
 * project:       FIT4Green/Optimizer
 * created:       09.10.2011 by ts
 * last modified: $LastChangedDate: 2012-06-13 16:36:15 +0200 (mié, 13 jun 2012) $ by $LastChangedBy: f4g.cnit $
 * revision:      $LastChangedRevision: 1493 $
 * 
 * short description:
 *   {To be completed}
 * ============================= /Header ==============================
 */
package f4g.optimizer.entropy.plan.constraint.factories;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import btrplace.model.Mapping;
import btrplace.model.Model;
import btrplace.model.Node;
import btrplace.model.VM;
import btrplace.model.constraint.Ban;
import btrplace.model.constraint.Fence;
import btrplace.model.constraint.Offline;
import btrplace.model.constraint.Online;
import btrplace.model.constraint.Root;
import btrplace.model.constraint.SatConstraint;

import f4g.optimizer.entropy.NamingService;
import f4g.optimizer.entropy.configuration.F4GConfigurationAdapter;
import f4g.optimizer.utils.Utils;
import f4g.schemas.java.metamodel.DatacenterType;
import f4g.schemas.java.metamodel.FIT4GreenType;
import f4g.schemas.java.metamodel.FrameworkCapabilitiesType;
import f4g.schemas.java.metamodel.ServerStatusType;
import f4g.schemas.java.metamodel.ServerType;
import f4g.schemas.java.metamodel.VirtualMachineType;



/**
 * {To be completed; use html notation, if necessary}
 * 
 */
public class ModelConstraintFactory extends ConstraintFactory {
    
	protected FIT4GreenType F4GModel;

	/**
	 * Constructor needing an instance of the SLAReader and an entropy
	 * configuration element.
	 */
	public ModelConstraintFactory(Model model, FIT4GreenType F4GModel) {
		super(model);
		log = Logger.getLogger(this.getClass().getName()); 
		this.F4GModel = F4GModel;
		
	}

	public List<SatConstraint> getModelConstraints() {
		
		List<SatConstraint> vs = new ArrayList<SatConstraint>();
		vs.addAll(getNodeTypeConstraints());
		vs.addAll(getFrameworkCapabilitiesConstraints());
	
		return vs;
		
	}
	
	public List<SatConstraint> getNodeTypeConstraints() {
		List<SatConstraint> v = new ArrayList<SatConstraint>();
		
		Set<VM> vms = new HashSet<VM>();
		vms.addAll(map.getAllVMs());
		Set<Node> onlines = new HashSet<Node>();
		Set<Node> offlines = new HashSet<Node>();
		Set<Node> empties = new HashSet<Node>();
		
		for(ServerType s : Utils.getAllServers(F4GModel)) {
	
			Node n = nodeNames.getElement(s.getFrameworkID());
			if(n!=null)	 {
				switch(s.getName()) {          
			    case CLOUD_CONTROLLER          : {
			    	log.debug("Cloud controller " + n.id());
			    	onlines.add(n); 
			    	empties.add(n);
			    	break;
			    }
			    case CLOUD_CLUSTER_CONTROLLER  : {
			    	log.debug("Cloud cluster controller " + n.id());
			    	onlines.add(n);
			    	empties.add(n);
			    	break;
			    }
			    case CLOUD_NODE_CONTROLLER     : break;
			    case TRADITIONAL_HOST          : break;
			    case OTHER                     : break;
			    default                        : break;
				}
			}
			
			//Entropy sees the nodes in transition as offline and mustn't switch them on.
			if(s.getStatus() == ServerStatusType.POWERING_OFF) {
				offlines.add(n);
			}
			if(s.getStatus() == ServerStatusType.POWERING_ON) {
				onlines.add(n);
				empties.add(n);
			}
		}
		if(onlines.size() != 0) {
			v.addAll(Online.newOnlines(onlines));	
		}
		
		if(offlines.size() != 0) {
			v.addAll(Offline.newOfflines(offlines));	
		}
		
		if(empties.size() != 0 && vms.size() != 0) {
			v.addAll(Ban.newBans(vms, empties));	
		}
		
		return v;
	}

	public List<SatConstraint> getFrameworkCapabilitiesConstraints() {
		List<SatConstraint> v = new LinkedList<SatConstraint>();
		int i = 0;
		List<DatacenterType> dcs = Utils.getAllDatacenters(F4GModel);
		for(DatacenterType dc : dcs) {
			i++;
			
			//Get all VMs of the DC
			Set<VM> vms = new HashSet<VM>();
			for(VirtualMachineType vm : Utils.getAllVMs(dc)) {
				VM myVM = vmNames.getElement(vm.getFrameworkID());
				if(myVM != null) {
					vms.add(myVM);
				}				
			}
						
			//get all nodes of the DC
			Set<Node> nodes = new HashSet<Node>();
			for(ServerType s : Utils.getAllServers(dc)) {
				Node n = nodeNames.getElement(s.getFrameworkID());
				if(n!=null){
					nodes.add(n);
				}
				
			}
			
			for(FrameworkCapabilitiesType fc : dc.getFrameworkCapabilities()) {
				
				if (fc.getVm() != null) {
					//for CP point of view, live migrate and moveVM are considered the same
					//migration inside the DC
					if (fc.getVm().isIntraLiveMigrate()
							|| fc.getVm().isIntraMoveVM()) {
						log.debug("VMs are allowed to move inside DC #" + i);

						//migration between the DCs
						if (fc.getVm().isInterLiveMigrate()
								|| fc.getVm().isInterMoveVM()) {
							log.debug("VMs of DC #" + i
									+ " are allowed to move to another DC");
						} else {
							log.debug("VMs of DC #" + i
									+ " are NOT allowed to move to another DC");
							if (dcs.size() >= 2) {
								if (vms.size() > 0 && nodes.size() > 0) {
									v.addAll(Fence.newFences(vms, nodes));
								}
							}

						}

					} else {
						log.debug("VMs are NOT allowed to move in DC #" + i);
						v.addAll(Root.newRoot(vms));
					}
				}
				
				//node framework capabilities
				if (fc.getNode() != null) {

					if(!fc.getNode().isPowerOff()) {
						
						Set<Node> onNodes = new HashSet<Node>();
						for(Node n : nodes) {
							if(map.isOnline(n)) {
								onNodes.add(n);
							}
						}	
						//keep ON nodes ON
						if(onNodes.size() != 0) {
							v.addAll(Online.newOnlines(onNodes));							
						}
						
					}
					if(!fc.getNode().isPowerOn()) {
						Set<Node> offNodes = new HashSet<Node>();
						for(Node n : nodes) {
							if(map.isOffline(n)) {
								offNodes.add(n);
							}
						}	
						//keep OFF nodes OFF
						if(offNodes.size() != 0) {
							v.addAll(Offline.newOfflines(offNodes));
						}
					}
					
				}
			}
		}
		
		return v;
	}
}
