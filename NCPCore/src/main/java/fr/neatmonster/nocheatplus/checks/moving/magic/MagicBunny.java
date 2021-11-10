/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.magic;

import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.block.Block;

import fr.neatmonster.nocheatplus.checks.combined.Improbable;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.utilities.location.LocUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;


/**
 * Horizontal acceleration magic
 */
public class MagicBunny {
    
    public static final double BUNNY_SLOPE_LOSS = 0.66;
    public static final double BUNNY_FRICTION = 0.99;
    /** Maximum hop delay. */
    public static final int BUNNYHOP_MAX_DELAY = 10; // ((https://www.mcpk.wiki/wiki/Jumping)
    /** Divisor vs. last hDist for minimum slow down. */
    private static final double bunnyDivFriction = 160.0; // Rather in-air, blocks would differ by friction.
    private static final boolean ServerIsAtLeast1_13 = ServerVersion.compareMinecraftVersion("1.13") >= 0;


    /**
     * Test for bunny hop / bunny fly. Does modify data only if 0.0 is returned.
     * @param from
     * @param to
     * @param player
     * @param hDistance
     * @param hAllowedDistance
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param sprinting
     * @param data
     * @param cc
     * @param tags
     * @return hDistanceAboveLimit
     */
    public static double bunnyHop(final PlayerLocation from, final PlayerLocation to, final Player player,
                                  final double hAllowedDistance, double hDistanceAboveLimit, final boolean sprinting, 
                                  final PlayerMoveData thisMove, final PlayerMoveData lastMove, 
                                  final MovingData data, final MovingConfig cc, final Collection<String> tags, final double speedAmplifier) {

        // ...Clean-up pending.
        // SHOULD: Rework the mechanic.
        
        // Shortcuts
        boolean allowHop = true; 
        boolean double_bunny = false;
        to.collectBlockFlags(); // Just to be sure.
        final double finalSpeed = thisMove.hAllowedDistance;
        final double hDistance = thisMove.hDistance;
        final double yDistance = thisMove.yDistance;
        final double baseSpeed = thisMove.hAllowedDistanceBase;
        final boolean headObstructed = thisMove.headObstructed || lastMove.headObstructed && lastMove.toIsValid;
        /** Catch-all multiplier for all those cases where bunnyhop activation can happen at lower accelerations.*/
        final boolean needLowerMultiplier = Magic.touchedSlipperyBlock(thisMove) || headObstructed || !Double.isInfinite(speedAmplifier);
        final PlayerMoveData pastMove2 = data.playerMoves.getSecondPastMove();
        final PlayerMoveData pastMove3 = data.playerMoves.getThirdPastMove();
        final PlayerMoveData pastMove4 = data.playerMoves.getPastMove(3);


        ///////////////////////////////////////////////////////////////////
        // After hop checks ("bunnyfly" phase, special cases)            //
        ///////////////////////////////////////////////////////////////////
        if (lastMove.toIsValid && data.bunnyhopDelay > 0 && hDistance > baseSpeed) {
            allowHop = false;
            final int hopTime = BUNNYHOP_MAX_DELAY - data.bunnyhopDelay;

            // Bunnyfly phase (decreasing speed due to friction)
            if (lastMove.hDistance > hDistance) { 

                final double hDistDiff = lastMove.hDistance - hDistance;
                // Slope (directly after hop but before friction): the initial/bunnyhop acceleration needs to drop sharply at first.
                // Ensure relative speed decrease vs. hop is met somehow. 
                if (data.bunnyhopDelay == 9 && hDistDiff >= bunnyLossMod(data, headObstructed) * (lastMove.hDistance - baseSpeed)) {
                    tags.add("bunnyslope");
                    hDistanceAboveLimit = 0.0;
                }
                // Bunny friction: very few air friction than ordinary.
                else if (bunnyFrictionEnvelope(hDistDiff, lastMove.hDistance, hDistanceAboveLimit, hDistance, baseSpeed)) {
                    
                    // Now, speed needs to decrease by some minimal amount per event. 
                    final double frictionDist = lastMove.hDistance * Magic.FRICTION_MEDIUM_AIR;
                    final double maxSpeed = baseSpeed * modBunny(headObstructed, data); 
                    final double allowedSpeed = maxSpeed * Math.pow(BUNNY_FRICTION, hopTime); 
                    // Speed is decreasing properly, allow the move.
                    if (hDistance <= allowedSpeed 
                        // Extremely little in-air decrease. Observed with slopes.
                        || Magic.jumpedUpSlope(data, to, 13, 0.1) && hDistDiff <= 0.00001 && Magic.inAir(thisMove)
                        // Transition from head obstructed to head free, only apply air friction
                        // TODO: Not enough, the previous movement would touch the ground first, making it fail.
                        || Magic.headWasObstructedRecently(data, 30) && hDistance <= frictionDist
                        // TODO: Transitions: head obstructed on ice - headobstructed on normal ground
                        // TODO: Hop-after-velocity
                        // TODO: Block edge skim, rather in lowjump but not always.
                        ) {
                        tags.add("bunnyfriction");
                        hDistanceAboveLimit = 0.0;
                    } 
                    // Likely cheating: attempting to jump too long despite still being in the friction envelope set by NCP
                    // Slowly update Improbable which each out-of-envelope friction phase (Note: this method is called twice in 
                    // hDistanceAfterFailure, so we need to feed lower weights in order to prevent too fast VL escalation)
                    //  else {
                    //      Improbable.feed(player, (float) 0.008, System.currentTimeMillis()); 
                    //      tags.add("bunnyspeed");
                    //  }

                    // ... one move between toonground and liftoff remains for hbuf ... 
                    // Bunnyfly phase has ended though the player is yet to be on ground, keep prolonging the phase.
                    if (data.bunnyhopDelay == 1 && !thisMove.to.onGround && !to.isResetCond()) {
                        data.bunnyhopDelay++;
                        tags.add("bunnyfly(keep)");
                    }
                    else tags.add("bunnyfly(" + data.bunnyhopDelay + ")");
                }
            } 

            // Do allow hop for special cases (reset delay).
            if (!allowHop) { 

                // Reset delay with head obstructed.
                // TODO: More precise confinment on acceleration: not sure how feasable since it tends to be quite incosistent.
                if (
                    // 0: Accel envelope.
                    ((hDistance / baseSpeed <= 1.92 || hDistance / lastMove.hDistance <= 1.92)
                    // 0: Negligeble acceleration, then abrupt acceleration from last move (0.204 -> 0.211 -> 0.5) (+0.3 gain). 
                    // Applies for normal ground only, as observed.
                    || !Magic.touchedIce(thisMove) && hDistance / lastMove.hDistance > 1.947 && lastMove.hDistance / pastMove2.hDistance < 1.1
                    && hDistance - lastMove.hDistance > lastMove.hDistance)
                    // 0: Ground conditions.
                    && (
                        // 1: The usual case: fully air move -> air-ground -> ground-air/ground (allow hop).
                        Magic.inAir(pastMove2) && !lastMove.from.onGround && lastMove.to.onGround && thisMove.from.onGround 
                        // 1: Sliding on ice: air-ground -> ground-ground -> ground-air
                        || Magic.touchedIce(pastMove2) && pastMove2.to.onGround && lastMove.from.onGround 
                        && lastMove.to.onGround && thisMove.from.onGround && !thisMove.to.onGround
                    )
                    // 0: Head obstruction conditions. Check for previous bunnyhop to prevent too easy abuse.
                    && !lastMove.bunnyHop && headObstructed
                    // 0: Multi-step speed increase, having increasingly higher speed with two moves. 0.3->0.4(bunnyhop)->0.5(VL, reset delay again)
                    // We use final speed and not base here to account for ice friction.
                    || !pastMove2.bunnyHop && lastMove.bunnyHop && headObstructed && hopTime == 1 && thisMove.from.onGround 
                    && lastMove.hDistance > pastMove2.hDistance && hDistance > lastMove.hDistance
                    && hDistance - lastMove.hDistance >= finalSpeed * 0.24 && hDistance - lastMove.hDistance < finalSpeed * 0.8) {
                    tags.add("headbangbunny");
                    allowHop = true;
                    data.clearHAccounting();
                }
                
                // 2x horizontal speed increase detection.
                // Introduced with commit: https://github.com/NoCheatPlus/NoCheatPlus/commit/0d52467fc2ea97351f684f0873ad13da250fd003
                // Perhaps we could confine this multi-step speed increase to legacy versions, doesn't seem to apply, ever.
                else if (hDistance - lastMove.hDistance >= baseSpeed * 0.5 && hopTime == 1 && !data.sfLowJump
                        && lastMove.yDistance >= -Magic.GRAVITY_MAX / 2.0 && lastMove.yDistance <= 0.0 && yDistance >= 0.4 
                        && lastMove.touchedGround) {
                    tags.add("doublebunny");
                    allowHop = double_bunny = true;
                }
                // Introduced with commit: https://github.com/Updated-NoCheatPlus/NoCheatPlus/commit/2ee891a427a047010f7358a7b246dd740398fa12
                else if (data.bunnyhopDelay <= 6 && !thisMove.headObstructed && !data.sfLowJump
                        && (thisMove.from.onGround || thisMove.touchedGroundWorkaround)) {
                    tags.add("ediblebunny");
                    allowHop = true;  
                }
                // Mild double acceleration with slime slopes. (bunnyhop->bunnyhop)
                else if (Magic.jumpedUpSlope(data, to, 13, 0.1) && lastMove.bunnyHop 
                        && thisMove.from.onGround && lastMove.to.onGround && !lastMove.from.onGround
                        && hDistance > lastMove.hDistance && hDistance / lastMove.hDistance <= 1.08
                        && (to.getBlockFlags() & BlockProperties.F_BOUNCE25) != 0) {
                    tags.add("bouncebunny");
                    allowHop = true;
                }
            }
        } 


        //////////////////////////////////////////////////
        // Bunnyfriction with lost ground               // 
        //////////////////////////////////////////////////
        // bunnyhop-> bunnyslope-> bunnyfriction-> ground-> microjump(still bunnyfriction)-> bunnyfriction
        //or bunnyhop-> ground-> slidedown-> bunnyfriction
        // Hit ground but slipped away by somehow and still remain bunny friction
        final double inc = ServerIsAtLeast1_13 ? 0.03 : 0;
        final double hopMargin = (data.bunnyhopTick > 0 ? (data.bunnyhopTick > 2 ? 1.0 + inc : 1.11 + inc) : 1.22 + inc);

        if (lastMove.toIsValid && data.bunnyhopDelay <= 0 && data.lastbunnyhopDelay > 0
            && lastMove.hDistance > hDistance && baseSpeed > 0.0 && hDistance / baseSpeed < hopMargin) {
            
            final double hDistDiff = lastMove.hDistance - hDistance;
            if (bunnyFrictionEnvelope(hDistDiff, lastMove.hDistance, hDistanceAboveLimit, hDistance, baseSpeed)) {
                //if (data.lastbunnyhopDelay == 8 && thisMove.from.onGround && !thisMove.to.onGround) {
                //    data.lastbunnyhopDelay++;
                //    tags.add("bunnyfriction(keep)"); // TODO: Never happen?
                //} else 

                if (hDistDiff < 0.01) {
                    // Allow the move
                    hDistanceAboveLimit = 0.0;
                    tags.add("lostbunny");
                    
                    // Remove lowjump in this hop, prevent false in next hop
                    if (data.sfLowJump) {
                        data.sfLowJump = false;
                        tags.add("bunny_no_low");
                        data.bunnyhopDelay = data.lastbunnyhopDelay - 1;
                        data.lastbunnyhopDelay = 0;
                    }
                } 
                else data.lastbunnyhopDelay = 0;
            } 
            else data.lastbunnyhopDelay = 0;
        }

        
        //////////////////////////////////////////////////////////////////////////////////////////////
        // Bunnyhop model (singular peak up to roughly two times the allowed distance)              //
        //////////////////////////////////////////////////////////////////////////////////////////////
        // TODO: Needs better modeling. 
        //       Also need to simplify ground acceleration factors. 
        //       Dynamically scale based on: slipperiness/speed effects/head obstr, account for small legit gains and such. Question is, how? :)
        // TODO: Test bunny spike over all sorts of speeds + attributes.
        final double minJumpGain = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier);
        final double MinAccelMult = needLowerMultiplier ? 1.0274 : (!lastMove.toIsValid || lastMove.hDistance == 0.0 && lastMove.yDistance == 0.0) ? 1.11 : 1.314;
        final double MaxAccelMult = data.bunnyhopTick > 0 ? (data.bunnyhopTick > 2 ? 1.76 : 1.96) : 2.15;
        final double MaxAccelMult1 = data.bunnyhopTick > 0 ? (data.bunnyhopTick > 2 ? 1.9 : 2.1) : 2.3;

        // Only if we allow hopping and hDistance is higher than the allowed speed.
        if (allowHop && hDistance >= baseSpeed
            // 0: Acceleration envelope. 
            && (hDistance > MinAccelMult * baseSpeed) && (hDistance < MaxAccelMult * baseSpeed)
            // 0: (Not documented)
            || (yDistance > from.getyOnGround() || hDistance < MaxAccelMult1 * baseSpeed) 
            && lastMove.toIsValid && hDistance > MinAccelMult * lastMove.hDistance && hDistance < 2.15 * lastMove.hDistance
            // Prevent ice friction abuse: (hop(0.5)->normal(0.3)->hop(0.8)->normal(0.6)(...), can happen legitimately but not consecutively.)
            // Otherwise, allow this case to apply, even without head being obstructed.
            && (!pastMove2.bunnyHop || !pastMove3.bunnyHop && !pastMove4.bunnyHop || !thisMove.headObstructed)
            ){
            
            // Pre-condition: normal jumping envelope, not a lowjump or a noLowJump flag is set for thisMove
            if (data.liftOffEnvelope == LiftOffEnvelope.NORMAL && (!data.sfLowJump || data.sfNoLowJump) 
                // 0: Y-distance envelope.
                && (
                    // 1: Normal jumping.
                    yDistance > 0.0 && yDistance > minJumpGain - Magic.GRAVITY_SPAN
                    // 1: Too short with head obstructed.
                    || headObstructed
                    // 1: Hop without y distance increase at moderate h-speed (Legacy, still needed?)
                    || yDistance >= 0.0 && (cc.sfGroundHop || yDistance == 0.0 && !lastMove.touchedGroundWorkaround && !lastMove.from.onGround)
                    && baseSpeed > 0.0 && hDistance / baseSpeed < 1.5 
                    && (hDistance / lastMove.hDistance < 1.35 || hDistance / baseSpeed < 1.35)
                    // 1: Landing on ground with negative yDistance left. Observed with slime blocks.
                    || yDistance < 0.0 && (from.getBlockFlags() & BlockProperties.F_BOUNCE25) != 0 
                    // 1: Y slopes. TODO: Max gravity, rather?
                    || Magic.jumpedUpSlope(data, from, 13, 0.1) && !lastMove.bunnyHop
                    && yDistance > 0.0 && yDistance <= minJumpGain - Magic.GRAVITY_MIN
                )
                // 0: Ground + jump phase conditions.
                && (
                    // 1: Ordinary/obvious lift-off.
                    data.sfJumpPhase == 0 && thisMove.from.onGround 
                    // 1: Touched ground somehow. 
                    || data.sfJumpPhase <= 1 && (thisMove.touchedGroundWorkaround || lastMove.touchedGround && !lastMove.bunnyHop) 
                    // 1: Double bunny.
                    || double_bunny
                )
                // 0: Other conditions to confine further.
                && (
                   // 1: Can't bunnyhop if in reset condition, unless in waterlogged (lift-off acceleration is already taken care of in setAllowedhDist)
                   (from.isInWaterLogged() && to.isInWaterLogged() || !from.isResetCond() && !to.isResetCond())
                   // 1: Allow this one
                   || data.isHalfGroundHalfWater
                )) {
                // Set the maximum delay before the player will be allowed to bunnyhop again. Bunnyfly starts.
                data.bunnyhopDelay = BUNNYHOP_MAX_DELAY;
                hDistanceAboveLimit = 0.0;
                thisMove.bunnyHop = true;
                tags.add("bunnyhop");
            }
            // This move reached at least the acceleration envelopes.
            else tags.add("bunnyenv");
        }
        return hDistanceAboveLimit;
    }


    /**
     * After bunnyhop friction envelope (very few air friction).
     * Call if the player is in a "bunnyfly" phase and the distance is higher than allowed.
     * Requires last move's data.
     *
     * @param hDistDiff 
     *            Difference from last to current hDistance
     * @param lastHDistance
     *            hDistane before current
     * @param hDistanceAboveLimit
     * @param currentHDistance
     * @param currentAllowedBaseSpeed 
     *            Applicable base speed (not final;  not taking into account other mechanics, like friction)
     * @return
     */
    public static boolean bunnyFrictionEnvelope(final double hDistDiff, final double lastHDistance, final double hDistanceAboveLimit, 
                                                final double currentHDistance, final double currentAllowedBaseSpeed) {

        // TODO: Conditions may be too loose as of now. Could be more strict.
        if (currentHDistance > lastHDistance) {
            return false;
        }
        return  hDistDiff >= lastHDistance / bunnyDivFriction 
                || hDistDiff >= hDistanceAboveLimit / 33.3 
                || hDistDiff >= (currentHDistance - currentAllowedBaseSpeed) * (1.0 - Magic.FRICTION_MEDIUM_AIR);
    }
    

    /**
     * Retrieve the appropriate modifier to calculate the relative speed decrease with
     * @param data
     * @param headObstructed
     * @return the modifier
     */
    public static double bunnyLossMod(final MovingData data, final boolean headObstructed) {
        final PlayerMoveData lastMove = data.playerMoves.getFirstPastMove();
        return 
               // Blue ice
               Magic.touchedBlueIce(lastMove) ? (headObstructed ? BUNNY_SLOPE_LOSS/6.346 : BUNNY_SLOPE_LOSS/4.647) :
               // Ice/packed Ice
               Magic.touchedIce(lastMove) ? (headObstructed ? BUNNY_SLOPE_LOSS/5.739 : BUNNY_SLOPE_LOSS/4.150) :
               // Slimes and beds
               Magic.touchedBouncyBlock(lastMove) ? (headObstructed ? BUNNY_SLOPE_LOSS/1.74 : BUNNY_SLOPE_LOSS/1.11) :
               // Ordinary
               headObstructed ? BUNNY_SLOPE_LOSS/1.4 : BUNNY_SLOPE_LOSS
            ;
    }
    

    /**
     * Retrieve the appropriate modifier to increase allowed base speed with in bunnyfly
     * @param headObstructed
     * @param data
     * @return the modifier
     */
    public static double modBunny(final boolean headObstructed, final MovingData data) {

        // TOOD: Past move tracking is not a good reference due to jump potions, unless we track a considerable amount of moves
        //      (Performance concerns), switch to time-based checks? ex.: wasOnIceRecently(6000)
        return  
                // Ice (friction takes care with head obstr, so no need to increase the factor.)
                // Pick the higher factor if no momentum ticks are present
                // (prevents random false positives when hopping off the spot)
                Magic.wasOnIceRecently(data) ? ((data.bunnyhopTick > 0 || headObstructed) ? 1.5415 : 1.7) :
                // Slimes / beds
                Magic.wasOnBouncyBlockRecently(data) ? (headObstructed ? 1.9 : 1.4467) :
                // Ordinary
                headObstructed ? 1.474 : (data.bunnyhopTick > 0 ? 1.09 : 1.255)
            ;
    }
}