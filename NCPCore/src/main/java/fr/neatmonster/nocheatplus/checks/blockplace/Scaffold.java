package fr.neatmonster.nocheatplus.checks.blockplace;

import java.util.LinkedList;
import java.util.List;

import fr.neatmonster.nocheatplus.components.registry.feature.TickListener;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;

public class Scaffold extends Check {
	
	public Scaffold() {
		super(CheckType.BLOCKPLACE_SCAFFOLD);
    }
	
	final static double MAX_ANGLE = Math.toRadians(90);

	public boolean check(Player player, BlockFace placedFace, final IPlayerData pData,
						 final BlockPlaceData data, final BlockPlaceConfig cc, final boolean isCancelled,
						 final double yDistance, final int jumpPhase, final boolean extraChecks) {
		boolean cancel = false;
		Scaffold thisCheck = this;
		List<String> tags = new LinkedList<>();
		
		final Vector placedVector = new Vector(placedFace.getModX(), placedFace.getModY(), placedFace.getModZ());
	    float placedAngle = player.getLocation().getDirection().angle(placedVector);
	    long now = System.currentTimeMillis();
	        
	    // Angle Check
	    if (cc.scaffoldAngle && placedAngle > MAX_ANGLE) {
	    	ViolationData vd = new ViolationData(this, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
	    	tags.add("Angle");
	    	if (vd.needsParameters()) {
	    		vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
	    	}
	    	cancel = executeActions(vd).willCancel();
			data.scaffoldVL += 1D;
	    }
	    
	    // Average time check
	    if (!isCancelled && cc.scaffoldTime && data.sneakTime + 150 < now && !player.hasPotionEffect(PotionEffectType.SPEED)) {
			// TODO: need to make this more efficient
			if (data.placeTime.size() > 2) {
				long avg = 0;
				for (int i = 0; i < data.placeTime.size(); i++) {
					avg += data.placeTime.get(i);
				}
				avg = avg / data.placeTime.size();
				if (avg < cc.scaffoldTimeAvg) {
					ViolationData vd = new ViolationData(this, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
			    	tags.add("Time");
			    	if (vd.needsParameters()) {
			    		vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
			    	}
					cancel = executeActions(vd).willCancel();
					data.scaffoldVL += 1D;
				}
				data.lastPlaceAvg = avg;
				data.placeTime.clear();
				data.lastPlaceTime = 0L;
				
			} else {
				if (data.lastPlaceTime == 0) {
					data.lastPlaceTime = now;
				} else {
					long diff = now - data.lastPlaceTime;
					data.placeTime.add(diff);
					data.lastPlaceTime = now;
					if (diff <= data.lastPlaceAvg && data.lastPlaceAvg < cc.scaffoldTimeAvg) {
						ViolationData vd = new ViolationData(this, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
				    	tags.add("TimeAvg");
				    	if (vd.needsParameters()) {
				    		vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
				    	}
						cancel = executeActions(vd).willCancel();
						data.scaffoldVL += 1D;
					}
				}
			}
		}

		// Sprint check
		long diff = System.currentTimeMillis() - data.sprintTime;
		if (extraChecks && cc.scaffoldSprint && diff < 400 && yDistance < 0.1 && jumpPhase < 4) {
			ViolationData vd = new ViolationData(this, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
			tags.add("Sprint");
			if (vd.needsParameters()) {
				vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
			}
			cancel = executeActions(vd).willCancel();
			data.scaffoldVL += 1D;
		}

		data.currentTick = TickTask.getTick();

	    // Pitch Check
		if (cc.scaffoldPitch) {
			data.lastPitch = player.getLocation().getPitch();
			TickListener pitchTick = new TickListener() {
				@Override
				public void onTick(int tick, long timeLast) {
					// Needs to be run on the next tick
					// Most likely better way to to this with TickTask but this works as well
					if (TickTask.getTick() != data.currentTick) {
						float diff = Math.abs(data.lastPitch - player.getLocation().getPitch());

						if (diff > cc.scaffoldPitchDiff) {
							data.scaffoldVL += 1D;
							ViolationData vd = new ViolationData(thisCheck, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
							tags.add("Pitch"); // TODO: Do we need to use tags here?
							if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
							data.cancelNextPlace = executeActions(vd).willCancel();
							tags.clear();
						}
						TickTask.removeTickListener(this);
					}
				}
			};
			TickTask.addTickListener(pitchTick);
		}

		// Tool Switch
		if (cc.scaffoldToolSwitch) {

			data.lastSlot = player.getInventory().getHeldItemSlot();
			TickListener toolSwitchTick = new TickListener() {
				@Override
				public void onTick(int tick, long timeLast) {
					// Needs to be run on the next tick
					// Most likely better way to to this with TickTask but this works as well
					if (data.currentTick != TickTask.getTick()) {
						if (data.lastSlot != player.getInventory().getHeldItemSlot()) {
							data.scaffoldVL += 1D;
							ViolationData vd = new ViolationData(thisCheck, player, data.scaffoldVL, 1, pData.getGenericInstance(BlockPlaceConfig.class).scaffoldActions);
							tags.add("ToolSwitch"); // TODO: Do we need to use tags here?
							if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
							data.cancelNextPlace = executeActions(vd).willCancel();
							tags.clear();
						}
						TickTask.removeTickListener(this);
					}
				}
			};
			TickTask.addTickListener(toolSwitchTick);
		}
		
		tags.clear();
		return cancel;
	}

}