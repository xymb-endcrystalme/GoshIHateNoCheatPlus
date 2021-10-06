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
package fr.neatmonster.nocheatplus.checks.moving.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.World;
import org.bukkit.block.Block;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.combined.Improbable;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGround;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.magic.InAirRules;
import fr.neatmonster.nocheatplus.checks.moving.magic.InLiquidRules;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.moving.velocity.VelocityFlags;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Bridge1_17;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.compat.BridgeMisc;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker.Direction;
import fr.neatmonster.nocheatplus.components.modifier.IAttributeAccess;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.logging.Streams;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.CheckUtils;
import fr.neatmonster.nocheatplus.utilities.PotionUtil;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.location.LocUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

/**
 * The counterpart to the CreativeFly check. People that are not allowed to fly get checked by this. It will try to
 * identify when they are jumping, check if they aren't jumping too high or far, check if they aren't moving too fast on
 * normal ground, while sprinting, sneaking, swimming, etc.
 */
public class SurvivalFly extends Check {

    // Server versions ugly but gets the job done...
    private final boolean ServerIsAtLeast1_9 = ServerVersion.compareMinecraftVersion("1.9") >= 0;
    private final boolean ServerIsAtLeast1_10 = ServerVersion.compareMinecraftVersion("1.10") >= 0;
    private final boolean ServerIsAtLeast1_13 = ServerVersion.compareMinecraftVersion("1.13") >= 0;
    /** Maximum hop delay. */
    private static final int bunnyHopMax = 10;
    private boolean snowFix;
    /** Flag to indicate whether the buffer should be used for this move (only work inside setAllowedhDist). */
    private boolean bufferUse;
    // TODO: Friction by block to walk on (horizontal only, possibly to be in BlockProperties rather).
    /** To join some tags with moving check violations. */
    private final ArrayList<String> tags = new ArrayList<String>(15);
    private final ArrayList<String> justUsedWorkarounds = new ArrayList<String>();
    private final Set<String> reallySneaking = new HashSet<String>(30);
    /** For temporary use: LocUtil.clone before passing deeply, call setWorld(null) after use. */
    private final Location useLoc = new Location(null, 0, 0, 0);
    private final BlockChangeTracker blockChangeTracker;
    // TODO: handle
    private final AuxMoving aux = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(AuxMoving.class);
    private IGenericInstanceHandle<IAttributeAccess> attributeAccess = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(IAttributeAccess.class);
    //private final Plugin plugin = Bukkit.getPluginManager().getPlugin("NoCheatPlus");

    /**
     * Some note for mcbe compatibility:
     * - New step pattern 0.42-0.58-0.001 ?
     * - Maximum step height 0.75 ?
     * - Ladder descends speed 0.2
     * - Jump on grass_path blocks will result in jump height 0.42 + 0.0625
     *   but next move friction still base on 0.42 ( not sure this does happen
     *   on others )
     * - honey block: yDistance < -0.118 && yDistance > -0.128 ?
     */

    /**
     * Instantiates a new survival fly check.
     */
    public SurvivalFly() {
        super(CheckType.MOVING_SURVIVALFLY);
        blockChangeTracker = NCPAPIProvider.getNoCheatPlusAPI().getBlockChangeTracker();
    }


    /**
     * Checks a player
     * @param player
     * @param from
     * @param to
     * @param multiMoveCount
     *            0: Ordinary, 1/2: first/second of a split move.
     * @param data
     * @param cc
     * @param tick
     * @param now
     * @param useBlockChangeTracker
     * @return
     */
    public Location check(final Player player, final PlayerLocation from, final PlayerLocation to, 
                          final int multiMoveCount, 
                          final MovingData data, final MovingConfig cc, final IPlayerData pData,
                          final int tick, final long now, final boolean useBlockChangeTracker) {

        tags.clear();
        // Shortcuts:
        final boolean debug = pData.isDebugActive(type);
        final PlayerMoveData thisMove = data.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = data.playerMoves.getFirstPastMove();
        final boolean isSamePos = from.isSamePos(to);
        final double xDistance, yDistance, zDistance, hDistance;
        final boolean HasHorizontalDistance;
        final boolean fromOnGround = thisMove.from.onGround;
        final boolean toOnGround = thisMove.to.onGround || useBlockChangeTracker && toOnGroundPastStates(from, to, thisMove, tick, data, cc);  // TODO: Work in the past ground stuff differently (thisMove, touchedGround?, from/to ...)
        final boolean resetTo = toOnGround || to.isResetCond();

        if (debug) {
            justUsedWorkarounds.clear();
            data.ws.setJustUsedIds(justUsedWorkarounds);
        }

        // Calculate some distances.
        if (isSamePos) {
            xDistance = yDistance = zDistance = hDistance = 0.0;
            HasHorizontalDistance = false;
        }
        else {
            xDistance = to.getX() - from.getX();
            yDistance = thisMove.yDistance;
            zDistance = to.getZ() - from.getZ();
            if (xDistance == 0.0 && zDistance == 0.0) {
                hDistance = 0.0;
                HasHorizontalDistance = false;
            }
            else {
                HasHorizontalDistance = true;
                hDistance = thisMove.hDistance;
            }
        }

        // Recover from data removal (somewhat random insertion point).
        if (data.liftOffEnvelope == LiftOffEnvelope.UNKNOWN) {
            data.adjustMediumProperties(from);
        }

        // Determine if the player is actually sprinting.
        final boolean sprinting;
        if (data.lostSprintCount > 0) {
            // Sprint got toggled off, though the client is still (legitimately) moving at sprinting speed.
            // NOTE: This could extend the "sprinting grace" period, theoretically, until on ground.
            if (resetTo && (fromOnGround || from.isResetCond()) || hDistance <= Magic.WALK_SPEED) {
                // Invalidate.
                data.lostSprintCount = 0;
                tags.add("invalidate_lostsprint");
                if (now <= data.timeSprinting + cc.sprintingGrace) {
                    sprinting = true;
                }
                else sprinting = false;
            }
            else {
                tags.add("lostsprint");
                sprinting = true;
                if (data.lostSprintCount < 3 && toOnGround || to.isResetCond()) {
                    data.lostSprintCount = 0;
                }
                else data.lostSprintCount --;
            }
        }
        else if (now <= data.timeSprinting + cc.sprintingGrace) {
            // Within grace period for hunger level being too low for sprinting on server side (latency).
            if (now != data.timeSprinting) {
                tags.add("sprintgrace");
            }
            sprinting = true;
        }
        else sprinting = false;

        // Use the player-specific walk speed.
        // TODO: Might get from listener.
        // TODO: Use in lostground?
        thisMove.walkSpeed = Magic.WALK_SPEED * ((double) data.walkSpeed / Magic.DEFAULT_WALKSPEED);

        data.setNextFriction(thisMove);







        ////////////////////////////////////
        // Mixed checks (lost ground)    ///
        ////////////////////////////////////
        final boolean resetFrom;
        if (fromOnGround || from.isResetCond()) resetFrom = true;

        // TODO: Extra workarounds for toOnGround (step-up is a case with to on ground)?
        // TODO: This isn't correct, needs redesign.
        // TODO: Quick addition. Reconsider entry points etc.
        else if (isSamePos) {

            if (useBlockChangeTracker && from.isOnGroundOpportune(cc.yOnGround, 0L, blockChangeTracker, data.blockChangeRef, tick)) {
                resetFrom = true;
                tags.add("pastground_from");
            }
            else if (lastMove.toIsValid) {
                // Note that to is not on ground either.
                resetFrom = LostGround.lostGroundStill(player, from, to, hDistance, yDistance, sprinting, lastMove, data, cc, tags);
            }
            else resetFrom = false;
        }
        else {
            // "Lost ground" workaround.
            // TODO: More refined conditions possible ?
            // TODO: Consider if (!resetTo) ?
            // Check lost-ground workarounds.
            resetFrom = LostGround.lostGround(player, from, to, hDistance, yDistance, sprinting, lastMove,  
                                              data, cc, useBlockChangeTracker ? blockChangeTracker : null, tags);
            // Note: if not setting resetFrom, other places have to check assumeGround...
        }

        if (thisMove.touchedGround) {
            if (!thisMove.from.onGround && !thisMove.to.onGround) {
                // Lost ground workaround has just been applied, check resetting of the dirty flag.
                // TODO: Always/never reset with any ground touched?
                data.resetVelocityJumpPhase(tags);
            }
            else if (multiMoveCount == 0 && thisMove.from.onGround && !lastMove.touchedGround
                    && TrigUtil.isSamePosAndLook(thisMove.from, lastMove.to)) {
                // Ground somehow appeared out of thin air (block place).
                data.setSetBack(from);
                if (debug) debug(player, "Adjust set back on move: from is now on ground.");
            }
        }

        // Renew the "dirty"-flag (in-air phase affected by velocity).
        // (Reset is done after checks run.) 
        if (data.isVelocityJumpPhase() || data.resetVelocityJumpPhase(tags)) {
            tags.add("dirty");
        }

        // HACK: Force sfNoLowJump by a flag.
        // TODO: Might remove that flag, as the issue for trying this has been resolved differently (F_HEIGHT8_1).
        // TODO: Consider setting on ground_height always?
        // TODO: Specialize - test for foot region?
        if ((from.getBlockFlags() & BlockProperties.F_ALLOW_LOWJUMP) != 0) {
            data.sfNoLowJump = true;
        }

        // Check if head is obstructed.
        thisMove.headObstructed = (yDistance > 0.0 ? from.isHeadObstructed(yDistance) : from.isHeadObstructed());

        // Moving half on farmland(or end_potal_frame) and half on water
        data.isHalfGroundHalfWater = (from.getBlockFlags() & BlockProperties.F_MIN_HEIGHT16_15) != 0 
                                     && from.isInWater() && !BlockProperties.isLiquid(from.getTypeId(from.getBlockX(), Location.locToBlock(from.getY() + 0.3), from.getBlockZ()));

        snowFix = (from.getBlockFlags() & BlockProperties.F_HEIGHT_8_INC) != 0;






        /////////////////////////////////////
        // Horizontal move                ///
        /////////////////////////////////////
        // TODO: Account for lift-off medium / if in air [i.e. account for medium + friction]?
        // Alter some data / flags.
        bufferUse = true;
        setHorizontalData(thisMove, from, to, data, pData, player, cc, xDistance, zDistance); 
        double hAllowedDistance = 0.0, hDistanceAboveLimit = 0.0, hFreedom = 0.0;


        // Run through all hDistance checks if the player has actually some horizontal distance
        if (HasHorizontalDistance) {

            final double attrMod = attributeAccess.getHandle().getSpeedAttributeMultiplier(player);
            // Set the allowed distance and determine the distance above limit
            hAllowedDistance = setAllowedhDist(player, sprinting, thisMove, data, cc, pData, from, true);
            hDistanceAboveLimit = hDistance - hAllowedDistance;

            // The player went beyond the allowed limit, execute the after failure checks.
            if (hDistanceAboveLimit > 0.0) {
                final double[] resultH = hDistAfterFailure(player, from, to, hAllowedDistance, hDistanceAboveLimit, 
                                                           sprinting, thisMove, lastMove, data, cc, pData, false);
                hAllowedDistance = resultH[0];
                hDistanceAboveLimit = resultH[1];
                hFreedom = resultH[2];
            }
            else {
                data.clearActiveHorVel();
                hFreedom = 0.0;
                if (resetFrom && data.bunnyhopDelay <= 6) {
                    data.bunnyhopDelay = 0;
                    data.bunnySlide = false;
                }
            }

            // The hacc subcheck (if enabled, always update)
            if (cc.survivalFlyAccountingH) {
                hDistanceAboveLimit = horizontalAccounting(data, hDistance, hDistanceAboveLimit, thisMove);
            }

            // Prevent players from walking on a liquid in a too simple way.
            if (!pData.hasPermission(Permissions.MOVING_SURVIVALFLY_WATERWALK, player)) {
                hDistanceAboveLimit = waterWalkChecks(data, player, hDistance, yDistance, thisMove, lastMove, 
                                                     fromOnGround, hDistanceAboveLimit, toOnGround, from, to);
            }
            
            // Prevent players from illegally sprinting.
            //if (!pData.hasPermission(Permissions.MOVING_SURVIVALFLY_SPRINTING, player)){
            //    hDistanceAboveLimit = sprintingChecks(sprinting, data, player, hDistance, hDistanceAboveLimit, thisMove,
            //                                          xDistance, zDistance, from);
            //}

            // Decrease bhop tick after checking
            if (data.bunnyhopTick > 0) {
                data.bunnyhopTick-- ;
            }

            // Count down for the soul speed enchant motion
            if (data.keepfrictiontick > 0) {
                data.keepfrictiontick-- ;
            }

            // A special(model) move from CreativeFly has been turned to a normal move again, count up for the incoming motion
            if (data.keepfrictiontick < 0) {
                data.keepfrictiontick++ ;
            }
        }
        // No horizontal distance present
        else {
            /*
             * TODO: Consider to log and/or remember when this was last time
             * cleared [add time distance to tags/log on violations].
             */
            data.clearActiveHorVel();
            thisMove.hAllowedDistanceBase = 0.0;
            thisMove.hAllowedDistance = 0.0;
            data.iceFrictionTick = 0;
            // TODO: Other properties should be set as well?
        }






        /////////////////////////////////////
        // Vertical move                  ///
        /////////////////////////////////////
        double vAllowedDistance = 0, vDistanceAboveLimit = 0;
        // TODO: thisMove.from.isInAnyMedium, rather?
        boolean InMedium = from.isOnHoneyBlock() || from.isInWeb() || from.isInBerryBush() 
                           || from.isOnClimbable() || from.isInLiquid() || from.isInPowderSnow()
                           || to.isOnClimbable();
        
        // Wild-card: allow step height from ground to ground, if not on/in a medium already.
        if (yDistance >= 0.0 && yDistance <= cc.sfStepHeight 
            && toOnGround && fromOnGround && !InMedium) {
            vAllowedDistance = cc.sfStepHeight;
            thisMove.allowstep = true;
            tags.add("groundstep");
        }

        // TODO: Test, adjust.
        // Powder snow (1.17+)
        else if (data.liftOffEnvelope == LiftOffEnvelope.POWDER_SNOW) {
            final double[] resultSnow = vDistPowderSnow(yDistance, from, to, cc, data, player);
            vAllowedDistance = resultSnow[0];
            vDistanceAboveLimit = resultSnow[1];
        }

        // Webs 
        else if (from.isInWeb()) {
            final double[] resultWeb = vDistWeb(player, thisMove, toOnGround, hDistanceAboveLimit, 
                                                now, data, cc, from);
            vAllowedDistance = resultWeb[0];
            vDistanceAboveLimit = resultWeb[1];
        }

         // Berry bush (1.15+)
        else if (from.isInBerryBush()){
            final double[] resultBush = vDistBush(player, thisMove, toOnGround, hDistanceAboveLimit, now, 
                                                  data, cc, from, fromOnGround);
            vAllowedDistance = resultBush[0];
            vDistanceAboveLimit = resultBush[1];
        }

        // HoneyBlock (1.14+)
        else if (from.isOnHoneyBlock()) {
            vAllowedDistance = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier);
            vDistanceAboveLimit = yDistance - vAllowedDistance;
            if (vDistanceAboveLimit > 0.0) tags.add("honeyasc");
        }

        // Climbable blocks
        else if (from.isOnClimbable()) {
            vDistanceAboveLimit = vDistClimbable(player, from, to, fromOnGround, toOnGround, thisMove, 
                                                 lastMove, yDistance, data);
        }

        // In liquid
        else if (from.isInLiquid()) { 
            final double[] resultLiquid = vDistLiquid(thisMove, from, to, toOnGround, yDistance, lastMove, data, player, cc);
            vAllowedDistance = resultLiquid[0];
            vDistanceAboveLimit = resultLiquid[1];

            // The frition jump phase has to be set externally.
            if (vDistanceAboveLimit <= 0.0 && yDistance > 0.0 
                && Math.abs(yDistance) > Magic.swimBaseSpeedV(Bridge1_13.isSwimming(player))) {
                data.setFrictionJumpPhase();
            }
        }

        // Fallback to in-air checks
        else {
            final double[] resultAir = vDistAir(now, player, from, fromOnGround, resetFrom, 
                                               to, toOnGround, resetTo, hDistanceAboveLimit, yDistance, 
                                               multiMoveCount, lastMove, data, cc, pData);
            vAllowedDistance = resultAir[0];
            vDistanceAboveLimit = resultAir[1];
        }

        // TODO: Better place for checking for moved blocks [redesign for intermediate result objects?].
        // Vertical push/pull.
        // (Horizontal is done in hDistanceAfterFailure)
        if (useBlockChangeTracker && vDistanceAboveLimit > 0.0) {
            double[] blockMoveResult = getVerticalBlockMoveResult(yDistance, from, to, tick, data);
            if (blockMoveResult != null) {
                vAllowedDistance = blockMoveResult[0];
                vDistanceAboveLimit = blockMoveResult[1];
            }
        }

        // Debug output.
        final int tagsLength;
        if (debug) {
            outputDebug(player, to, data, cc, hDistance, hAllowedDistance, hFreedom, 
                        yDistance, vAllowedDistance, fromOnGround, resetFrom, toOnGround, 
                        resetTo, thisMove, vDistanceAboveLimit);
            tagsLength = tags.size();
            data.ws.setJustUsedIds(null);
        }
        else tagsLength = 0; // JIT vs. IDE.





        //////////////////////////////////////
        // Handle violations               ///
        //////////////////////////////////////
        final boolean inAir = Magic.inAir(thisMove);
        final double result = (Math.max(hDistanceAboveLimit, 0D) + Math.max(vDistanceAboveLimit, 0D)) * 100D;
        if (result > 0D) {

            final Location vLoc = handleViolation(now, Double.isInfinite(result) ? 30.0 : result, player, from, to, data, cc);
            if (inAir) {
                data.sfVLInAir = true;
            }
            if (vLoc != null) {
                return vLoc;
            }
        }
        else {
            // Slowly reduce the level with each event, if violations have not recently happened.
            // TODO: Switch to move count instead of time (!).
            if (data.getPlayerMoveCount() - data.sfVLTime > cc.survivalFlyVLFreezeCount 
                && (!cc.survivalFlyVLFreezeInAir || !inAir
                    // Favor bunny-hopping slightly: clean descend.
                    || !data.sfVLInAir
                    && data.liftOffEnvelope == LiftOffEnvelope.NORMAL
                    && lastMove.toIsValid 
                    && lastMove.yDistance < -Magic.GRAVITY_MIN
                    && thisMove.yDistance - lastMove.yDistance < -Magic.GRAVITY_MIN)) {
                // Relax VL.
                data.survivalFlyVL *= 0.95;
                // Finally check horizontal buffer regain.
                if (hDistanceAboveLimit < 0.0  && result <= 0.0 
                    && !isSamePos && data.sfHorizontalBuffer < cc.hBufMax) {
                    // TODO: max min other conditions ?
                    hBufRegain(hDistance, Math.min(0.2, Math.abs(hDistanceAboveLimit)), data, cc);
                }
            }
        }






        //////////////////////////////////////////////////////////////////////////////////////////////
        //  Set data for normal move or violation without cancel (cancel would have returned above) //
        //////////////////////////////////////////////////////////////////////////////////////////////
        // Adjust lift off envelope to medium
        // TODO: Web before liquid? Climbable?
        // TODO: isNextToGround(0.15, 0.4) allows a little much (yMargin), but reduces false positives.
        // TODO: nextToGround: Shortcut with block-flags ?
        final LiftOffEnvelope oldLiftOffEnvelope = data.liftOffEnvelope;
        if (to.isInLiquid()) {
            if (fromOnGround && !toOnGround 
                && data.liftOffEnvelope == LiftOffEnvelope.NORMAL
                && data.sfJumpPhase <= 0 && !thisMove.from.inLiquid) {
                // KEEP
            }
            else if (to.isNextToGround(0.15, 0.4)) {
                // Consent with ground.
                data.liftOffEnvelope = LiftOffEnvelope.LIMIT_NEAR_GROUND;
            }
            // TODO: Distinguish strong limit from normal.
            else data.liftOffEnvelope = LiftOffEnvelope.LIMIT_LIQUID;
        }
        else if (BlockProperties.isPowderSnow(to.getTypeId())) {
            data.liftOffEnvelope = LiftOffEnvelope.POWDER_SNOW;
        }
        else if (thisMove.to.inWeb) {
            data.liftOffEnvelope = LiftOffEnvelope.NO_JUMP; 
        }
        else if (thisMove.to.inBerryBush) {
            data.liftOffEnvelope = LiftOffEnvelope.BERRY_JUMP;
        }
        else if (thisMove.to.onHoneyBlock) {
            data.liftOffEnvelope = LiftOffEnvelope.HALF_JUMP;
        }
        else if (resetTo) {
            // TODO: This might allow jumping on vines etc., but should do for the moment.
            data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        }
        else if (thisMove.from.inLiquid) {
            if (!resetTo && data.liftOffEnvelope == LiftOffEnvelope.NORMAL
                && data.sfJumpPhase <= 0) {
                // KEEP
            }
            else if (to.isNextToGround(0.15, 0.4)) {
                // TODO: Problematic: y-distance slope can be low jump.
                data.liftOffEnvelope = LiftOffEnvelope.LIMIT_NEAR_GROUND;
            }
            // TODO: Distinguish strong limit.
            else data.liftOffEnvelope = LiftOffEnvelope.LIMIT_LIQUID;
        }
        else if (BlockProperties.isPowderSnow(from.getTypeId())) {
            data.liftOffEnvelope = LiftOffEnvelope.POWDER_SNOW;
        }
        else if (thisMove.from.inWeb) {
            data.liftOffEnvelope = LiftOffEnvelope.NO_JUMP; // TODO: Test.
        }
        else if (thisMove.from.inBerryBush) {
            data.liftOffEnvelope = LiftOffEnvelope.BERRY_JUMP;
        }
        else if (thisMove.from.onHoneyBlock) {
            data.liftOffEnvelope = LiftOffEnvelope.HALF_JUMP;
        }
        else if (resetFrom || thisMove.touchedGround) {
            data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        }
        else {
            // Keep medium.
        }

        // Count how long one is moving inside of a medium.
        if (oldLiftOffEnvelope != data.liftOffEnvelope) {
            data.insideMediumCount = 0;
            data.combinedMediumHCount = 0;
            data.combinedMediumHValue = 0.0;
        }
        else if (!resetFrom || !resetTo) {
            data.insideMediumCount = 0;
        }
        else {
            data.insideMediumCount ++;
        }

        // Apply reset conditions.
        if (resetTo) {
            // The player has moved onto ground.
            if (toOnGround) {
                // Reset bunny-hop-delay.
                if (data.bunnyhopDelay > 0 && yDistance > 0.0 && to.getY() > data.getSetBackY() + 0.12 
                    && !from.isResetCond() && !to.isResetCond()) {
                    if (data.bunnyhopDelay > 6) data.lastbunnyhopDelay = data.bunnyhopDelay;
                    data.bunnyhopDelay = 0;
                    data.bunnySlide = false;
                    tags.add("resetbunny");
                }
            }
            // Reset data.
            data.setSetBack(to);
            data.sfJumpPhase = 0;
            data.clearAccounting();
            data.sfNoLowJump = false;
            if (data.sfLowJump && resetFrom) {
                // Prevent reset if coming from air (purpose of the flag).
                data.sfLowJump = false;
            }
            if (hFreedom <= 0.0 && thisMove.verVelUsed == null) {
                data.resetVelocityJumpPhase(tags);
            }
        }
        else if (resetFrom) {
            // The player moved from ground.
            data.setSetBack(from);
            data.sfJumpPhase = 1; // This event is already in air.
            data.clearAccounting();
            data.sfLowJump = false;
            // not resetting nolowjump (?)...
            // Don't reset velocity phase unless moving into resetcond.
            //            if (hFreedom <= 0.0 && data.verVelUsed == null && (!data.noFallAssumeGround || fromOnGround)) {
            //                data.resetVelocityJumpPhase(tags);
            //            }
        }
        else {
            data.sfJumpPhase ++;
            // TODO: Void-to-void: Rather handle unified somewhere else (!).
            if (to.getY() < 0.0 && cc.sfSetBackPolicyVoid) {
                data.setSetBack(to);
            }
        }
        
        // Adjust in-air counters.
        if (inAir) {
            if (yDistance == 0.0) {
                data.sfZeroVdistRepeat ++;
            }
            else data.sfZeroVdistRepeat = 0;
        }
        else {
            data.sfZeroVdistRepeat = 0;
            data.ws.resetConditions(WRPT.G_RESET_NOTINAIR);
            data.sfVLInAir = false;
        }

        // Horizontal velocity invalidation.
        if (hDistance <= (cc.velocityStrictInvalidation ? thisMove.hAllowedDistanceBase : thisMove.hAllowedDistanceBase / 2.0)) {
            data.clearActiveHorVel();
        }

        // Update unused velocity tracking.
        // TODO: Hide and seek with API.
        // TODO: Pull down tick / timing data (perhaps add an API object for millis + source + tick + sequence count (+ source of sequence count).
        if (debug) {
            // TODO: Only update, if velocity is queued at all.
            data.getVerticalVelocityTracker().updateBlockedState(tick, 
                    // Assume blocked with being in web/water, despite not entirely correct.
                    thisMove.headObstructed || thisMove.from.resetCond,
                    // (Similar here.)
                    thisMove.touchedGround || thisMove.to.resetCond);
            // TODO: TEST: Check unused velocity here too. (Should have more efficient process, pre-conditions for checking.)
            UnusedVelocity.checkUnusedVelocity(player, type, data, cc);
        }
      
        // Adjust friction.
        data.lastFrictionHorizontal = data.nextFrictionHorizontal;
        data.lastFrictionVertical = data.nextFrictionVertical;

        // Log tags added after violation handling.
        if (debug && tags.size() > tagsLength) {
            logPostViolationTags(player);
        }
        // Nothing to do, newTo (MovingListener) stays null
        return null;
    }
    




    
   /**
    * The horizontal accounting subcheck:
    * It monitors average combined-medium (e.g. air+ground or air+water) speed, with a rather simple bucket(s)-overflow mechanism.
    * We feed 1.0 whenever we're below the allowed BASE speed, and (actual / base) if we're above. 
    * (hAllowedDistanceBase is about what a player can run at without using special techniques like extra jumping, 
    * not necessarily the finally allowed speed).
    * 
    * @return hDistanceAboveLimit
    */
    private double horizontalAccounting(final MovingData data, double hDistance, double hDistanceAboveLimit, final PlayerMoveData thisMove) {
        
        final double fcmhv = Math.max(1.0, Math.min(10.0, thisMove.hDistance / thisMove.hAllowedDistanceBase));
        data.combinedMediumHCount ++;
        data.combinedMediumHValue += fcmhv;

        // TODO: Balance, where to check / use (...).
        if (data.combinedMediumHCount > 30) {
            // TODO: Early trigger (> 0,1,2,5?), for way too high values. [in that case don't reset]
            final double fcmh = data.combinedMediumHValue / (double) data.combinedMediumHCount;
            final double limitFCMH;
            if (data.bunnySlide) {
                limitFCMH = 1.70;
            }
            else if (data.liftOffEnvelope == LiftOffEnvelope.NORMAL) {
                limitFCMH = 1.34;
            }
            else if (data.liftOffEnvelope == LiftOffEnvelope.LIMIT_LIQUID 
                    || data.liftOffEnvelope == LiftOffEnvelope.LIMIT_NEAR_GROUND) {
                // 1.8.8 in-water moves with jumping near/on surface. 1.2 is max factor for one move (!).
                limitFCMH =  ServerIsAtLeast1_10 ? 1.05 : 1.1; 
            }
            else if (data.liftOffEnvelope == LiftOffEnvelope.POWDER_SNOW) {
                limitFCMH = 1.047;
            }
           /* else if (data.liftOffEnvelope == LiftOffEnvelope.BERRY_JUMP) {
                limitFCMH = 1.077;
            }
            else if (data.liftOffEnvelope == LiftOffEnvelope.HALF_JUMP) {
                limitFCMH = 1.025;
            }
            else if (data.liftOffEnvelope == LiftOffEnvelope.STAIRS) {
                limitFCMH = 1.44;
            }
            */
            // TODO: half_jump, berry_jump !
            else {
                limitFCMH = 1.0;
            }
            // TODO: Configurable / adjust by medium type.
            // TODO: Instead of velocityJumpPhase account for friction directly?
            // TODO: Fly-NoFly + bunny-water transitions pose issues.
            if (fcmh > limitFCMH && !data.isVelocityJumpPhase()) {
                hDistanceAboveLimit = Math.max(hDistanceAboveLimit, (hDistance * (fcmh - limitFCMH)));
                tags.add("hacc");
                // Reset for now.
                data.combinedMediumHCount = 0;
                data.combinedMediumHValue = 0.0;
            }
            else {
                // TODO: Other cases (1.0, between, ...)?
                data.combinedMediumHCount = 1;
                data.combinedMediumHValue = fcmhv;
            }
        }
        return hDistanceAboveLimit;
    }
    

   /**
    * Catch rather simple waterwalk cheat types. 
    * Do note that the speed for moving on the surface is restricted anyway in setAllowedhDist (in case these methods get bypassed).
    * 
    * @return hDistanceAboveLimit
    */
    private double waterWalkChecks(final MovingData data, final Player player, double hDistance, double yDistance, 
                                   final PlayerMoveData thisMove, final PlayerMoveData lastMove,
                                   final boolean fromOnGround, double hDistanceAboveLimit,
                                   final boolean toOnGround, final PlayerLocation from, final PlayerLocation to) {

        Material blockUnder = from.getTypeId(from.getBlockX(), Location.locToBlock(from.getY() - 0.3), from.getBlockZ());
        Material blockAbove = from.getTypeId(from.getBlockX(), Location.locToBlock(from.getY() + 0.1), from.getBlockZ());

        // Checks for 0 y deltas when on/in water
        if (hDistanceAboveLimit <= 0D && hDistance > 0.1D && yDistance == 0D && lastMove.toIsValid && lastMove.yDistance == 0D 
            && BlockProperties.isLiquid(to.getTypeId()) 
            && BlockProperties.isLiquid(from.getTypeId())
            && !toOnGround && !fromOnGround
            && !from.isHeadObstructed() && !to.isHeadObstructed() 
            && !Bridge1_13.isSwimming(player)
            ) {
            hDistanceAboveLimit = Math.max(hDistanceAboveLimit, hDistance);
            tags.add("liquidwalk");
        }

        // Checks for micro y deltas when moving above liquid.
        // TODO: Test if this could also apply in liquid (Might further restrict waterwalk cheats)
        if (blockUnder != null && BlockProperties.isLiquid(blockUnder) && BlockProperties.isAir(blockAbove)) {
            
            if (!data.isHalfGroundHalfWater && hDistanceAboveLimit <= 0D && hDistance > 0.11D && yDistance <= 0.1D 
                && !toOnGround && !fromOnGround
                && lastMove.toIsValid && lastMove.yDistance == yDistance 
                || lastMove.yDistance == yDistance * -1 && lastMove.yDistance != 0D
                && !from.isHeadObstructed() && !to.isHeadObstructed() 
                && !Bridge1_13.isSwimming(player)
                ) {

                // Prevent being flagged if a player transitions from a block to water and the player falls into the water.
                if (!(yDistance < 0.0 && yDistance != 0.0 && lastMove.yDistance < 0.0 && lastMove.yDistance != 0.0)) {
                    hDistanceAboveLimit = Math.max(hDistanceAboveLimit, hDistance);
                    tags.add("liquidmove");
                }
            }
        }
        return hDistanceAboveLimit;
    }

   
    ///**
    //* Checks for illegal sprinting modifications, such as sprinting backwards, sideways and on impossible conditions
    //* 
    // * @param sprinting
    //* @param hDistance
    //* @param hDistanceAboveLimit
    //* @return hDistanceAboveLimit
    //*/
    //private double sprintingChecks(final boolean sprinting, final MovingData data, final Player player,
    //                               double hDistance, double hDistanceAboveLimit, final PlayerMoveData thisMove,
    //                               double xDistance, double zDistance, final PlayerLocation from){
    //
    //    // TODO: Add sideways sprinting module (rather important for PVP)
    //    // TODO: Move the lowfoodsprint check here?
    //
    //    // Note: Using NCP's sprinting mechanics here could yield false positives, due to the current assumeSprint workaround which assumes players to be
    //    // sprinting all the time (when possible), even if they are just walking. 
    //    // Potential fixes: 
    //    // 1) Review if the workaround is still nedeed. (-> Seems like it's crucial for PVP since players will still get setbacked without this...);
    //    // 2) Confine assumeSprint more (-> How?);
    //    // 3) Simply use player#isSprinting() (But events could be missing, out of order or not sent for whatever reason, in a lostGround fashion...).
    //    
    //    // Vanilla MC disallows players from sprinting with blindness
    //    //if (player.isSprinting() && hDistance > thisMove.walkSpeed && player.hasPotionEffect(PotionEffectType.BLINDNESS)
    //    //    && data.lostSprintCount == 0 && !data.isVelocityJumpPhase()) {
    //    //    hDistanceAboveLimit = Math.max(hDistanceAboveLimit, (hDistance - thisMove.walkSpeed)); // Allow players to walk at walking pace, rather than invalidating all hDist
    //    //    tags.add("blindsprint");
    //    //    bufferUse = false;
    //    //}
    //    
    //    // Prevent players from sprinting backwards
    //    //if (player.isSprinting() && hDistance > thisMove.walkSpeed && data.lostSprintCount == 0 && !data.isVelocityJumpPhase()){
    //    //    if (TrigUtil.isMovingBackwards(xDistance, zDistance, LocUtil.correctYaw(from.getYaw()))){
    //    //        hDistanceAboveLimit = Math.max(hDistanceAboveLimit, (hDistance - thisMove.walkSpeed)); // Allow players to walk at walking pace, rather than invalidating all hDist
    //    //        tags.add("backsprint");
    //    //        bufferUse = false; // Mnh, too harsh?
    //    //    }
    //    //}
    //    return hDistanceAboveLimit;
    //}
    

   /**
    * Check for toOnGround past states
    * 
    * @param from
    * @param to
    * @param thisMove
    * @param tick
    * @param data
    * @param cc
    * @return
    */
    private boolean toOnGroundPastStates(final PlayerLocation from, final PlayerLocation to, 
                                         final PlayerMoveData thisMove, int tick, 
                                         final MovingData data, final MovingConfig cc) {
        
        // TODO: Heuristics / more / which? (too short move, typical step up moves, typical levels, ...)
        if (to.isOnGroundOpportune(cc.yOnGround, 0L, blockChangeTracker, data.blockChangeRef, tick)) {
            tags.add("pastground_to");
            return true;
        }
        else {
            return false;
        }
    }


    /**
     * Advance/Set horizontal data and increase some tick counters used in setAllowedhDist.
     * To keep a better overview of things.
     * @param thisMove
     * @param from
     * @param to
     * @param data
     * @param pData
     * @param player
     * @param cc
     */
    private void setHorizontalData(final PlayerMoveData thisMove, final PlayerLocation from, final PlayerLocation to, final MovingData data, 
                                   final IPlayerData pData, final Player player, final MovingConfig cc, final double xDistance, final double zDistance) {

        data.bunnyhopDelay--; // Decrease bunnyHopDelay before checking.
        data.lastbunnyhopDelay -= data.lastbunnyhopDelay > 0 ? 1 : 0;
        thisMove.downStream = from.isDownStream(xDistance, zDistance); // Set flag for swimming with the flowing direction of liquid.
        if (data.bunnyhopDelay <= 0) data.bunnySlide = false; // Reset if not in bunnfly.
        if (!data.bunnySlide) data.frictionAccel = 0.0;
        if (data.iceFrictionTick > 0) data.iceFrictionTick--;

        // Pass downstream for later uses
        // Wrong place actually. Put it here for a better overview.
        if (!data.isdownstream) data.isdownstream = thisMove.downStream;
        else if (from.isOnGround() && !from.isInLiquid()) data.isdownstream = false;

        // Jump with head obstructed and trap door on ice
        // Actually no way to detect if they are about to jump, so we cannot model this with bunnyhop, just give players a higher speed.
        if (thisMove.headObstructed 
            && from.isOnIce() 
            && (from.getBlockFlags() & BlockProperties.F_ATTACHED_LOW2_SNEW) != 0) {
            data.iceFrictionTick = 24;
            data.bunnyhopTick = 4;
        }
    }


    /**
     * Check for push/pull by pistons, alter data appropriately (blockChangeId).
     * 
     * @param yDistance
     * @param from
     * @param to
     * @param data
     * @return
     */
    private double[] getVerticalBlockMoveResult(final double yDistance, 
                                                final PlayerLocation from, final PlayerLocation to, 
                                                final int tick, final MovingData data) {
        /*
         * TODO: Pistons pushing horizontally allow similar/same upwards
         * (downwards?) moves (possibly all except downwards, which is hard to
         * test :p).
         */
        // TODO: Allow push up to 1.0 (or 0.65 something) even beyond block borders, IF COVERED [adapt PlayerLocation].
        // TODO: Other conditions/filters ... ?
        // Push (/pull) up.
        if (yDistance > 0.0) {
            if (yDistance <= 1.015) {
                /*
                 * (Full blocks: slightly more possible, ending up just above
                 * the block. Bounce allows other end positions.)
                 */
                // TODO: Is the air block wich the slime block is pushed onto really in? 
                if (from.matchBlockChange(blockChangeTracker, data.blockChangeRef, Direction.Y_POS, 
                        Math.min(yDistance, 1.0))) {
                    if (yDistance > 1.0) {
                        //                        // TODO: Push of box off-center has the same effect.
                        //                        final BlockChangeEntry entry = blockChangeTracker.getBlockChangeEntryMatchFlags(data.blockChangeRef, 
                        //                                tick, from.getWorld().getUID(), from.getBlockX(), from.getBlockY() - 1, from.getBlockZ(),
                        //                                Direction.Y_POS, BlockProperties.F_BOUNCE25);
                        //                        if (entry != null) {
                        //                            data.blockChangeRef.updateSpan(entry);
                        //                            data.prependVerticalVelocity(new SimpleEntry(tick, 0.5015, 3)); // TODO: HACK
                        //                            tags.add("past_bounce");
                        //                        }
                        //                        else 
                        if (to.getY() - to.getBlockY() >= 0.015) {
                            // Exclude ordinary cases for this condition.
                            return null;
                        }
                    }
                    tags.add("blkmv_y_pos");
                    final double maxDistYPos = yDistance; //1.0 - (from.getY() - from.getBlockY()); // TODO: Margin ?
                    return new double[]{maxDistYPos, 0.0};
                }
            }
            // (No else.)
            //            if (yDistance <= 1.55) {
            //                // TODO: Edges ca. 0.5 (or 2x 0.5).
            //                // TODO: Center ca. 1.5. With falling height, values increase slightly.
            //                // Simplified: Always allow 1.5 or less with being pushed up by slime.
            //                // TODO: 
            //                if (from.matchBlockChangeMatchResultingFlags(
            //                        blockChangeTracker, data.blockChangeRef, Direction.Y_POS, 
            //                        Math.min(yDistance, 0.415), // Special limit.
            //                        BlockProperties.F_BOUNCE25)) {
            //                    tags.add("blkmv_y_pos_bounce");
            //                    final double maxDistYPos = yDistance; //1.0 - (from.getY() - from.getBlockY()); // TODO: Margin ?
            //                    // TODO: Set bounce effect or something !?
            //                    // TODO: Bounce effect instead ?
            //                    data.addVerticalVelocity(new SimpleEntry(tick, Math.max(0.515, yDistance - 0.5), 2));
            //                    return new double[]{maxDistYPos, 0.0};
            //                }
            //            }
        }
        // Push (/pull) down.
        else if (yDistance < 0.0 && yDistance >= -1.0) {
            if (from.matchBlockChange(blockChangeTracker, data.blockChangeRef, Direction.Y_NEG, -yDistance)) {
                tags.add("blkmv_y_neg");
                final double maxDistYNeg = yDistance; // from.getY() - from.getBlockY(); // TODO: Margin ?
                return new double[]{maxDistYNeg, 0.0};
            }
        }
        // Nothing found.
        return null;
    }


    /**
     * Set hAllowedDistanceBase and hAllowedDistance in thisMove. Not exact,
     * check permissions as far as necessary, if flag is set to check them.
     * 
     * @param player
     * @param sprinting
     * @param thisMove
     * @param data
     * @param cc
     * @param checkPermissions
     *            If to check permissions, allowing to speed up a little bit.
     *            Only set to true after having failed with it set to false.
     * @return Allowed distance.
     */
    private double setAllowedhDist(final Player player, final boolean sprinting, 
                                   final PlayerMoveData thisMove, final MovingData data,
                                   final MovingConfig cc, final IPlayerData pData, final PlayerLocation from,
                                   final boolean checkPermissions) {

        // TODO: Optimize for double checking?
        // TODO: sfDirty: Better friction/envelope-based.
        final boolean isMovingBackwards   = TrigUtil.isMovingBackwards(thisMove.to.getX()-thisMove.from.getX(), thisMove.to.getZ()-thisMove.from.getZ(), LocUtil.correctYaw(from.getYaw()));  
        final boolean actuallySneaking    = player.isSneaking() && reallySneaking.contains(player.getName());
        final boolean isBlockingOrUsing   = data.isusingitem || player.isBlocking();
        final PlayerMoveData lastMove     = data.playerMoves.getFirstPastMove();
        final long now                    = System.currentTimeMillis(); 
        final double modHoneyBlock        = Magic.modSoulSand * (thisMove.to.onGround ? 0.8 : 1.75);
        final double modStairs            = isMovingBackwards ? 1.0 : thisMove.yDistance == 0.5 ? 1.85 : 1.325;
        final double modHopSprint         = (data.bunnyhopTick < 3 ? 1.15 : Magic.modSprint);
        final boolean sfDirty             = data.isVelocityJumpPhase(); 
        double hAllowedDistance           = 0D;
        double friction                   = data.lastFrictionHorizontal; // Friction to use with this move.
        boolean useBaseModifiers          = false;
        boolean useBaseModifiersSprint    = true;
        boolean useSneakModifier          = false;

  
        // Preliminary resets
        if (data.noslowhop != 0 && (sfDirty || (!data.isusingitem && !player.isBlocking()))) data.noslowhop = 0;
        if (!data.liftOffEnvelope.name().startsWith("LIMIT") || sfDirty) data.surfaceId = 0;
        if (thisMove.from.onIce) tags.add("hice");


        /////////////////////////////////////////////////////////////
        // Set the allowed horizontal distance according to medium //
        /////////////////////////////////////////////////////////////
        // TODO: Let's review *ALL* modifiers/factors so that they are closer to actual client speed :)
        // Webs
        if (thisMove.from.inWeb) {
            tags.add("hweb");
            data.iceFrictionTick = 0;
            hAllowedDistance = Magic.modWeb * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            useBaseModifiersSprint = false; 
            useBaseModifiers = true;
            useSneakModifier = true;
            friction = 0.0; 
        }

        // Powder snow
        // TODO: Scale speed according to freezing tick.
        else if (thisMove.from.inPowderSnow) {
            tags.add("hsnow");
            data.iceFrictionTick = 0;
            hAllowedDistance = Magic.modPowderSnow * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            if (thisMove.yDistance > 0.0 && thisMove.from.onGround && !thisMove.to.onGround) hAllowedDistance *= 2.3;
            friction = 0.0;
            useBaseModifiers = true;
            useSneakModifier = true;
        }

        // Berry bush
        else if (thisMove.from.inBerryBush) {
            tags.add("hbush");
            hAllowedDistance = Magic.modBush * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            if (thisMove.to.inBerryBush) hAllowedDistance *= 0.8;
            if (thisMove.yDistance > 0.0 && thisMove.from.onGround && !thisMove.to.onGround) hAllowedDistance *= 2.0;
            useSneakModifier = true;
            useBaseModifiers = true;
            friction = 0.0;
            
            // Multiprotocol plugins: allow normal walking.
            if ((from.getBlockFlags() & BlockProperties.F_ALLOW_LOWJUMP) != 0) {
               hAllowedDistance = thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            }
        }
        
        // Soulsand
        else if (thisMove.from.onSoulSand) {
            tags.add("hsoulsand");
            hAllowedDistance = Magic.modSoulSand * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            // SoulSpeed stuff
            // TODO: Actually scale modifier according to enchant level?
            if (BridgeEnchant.hasSoulSpeed(player)) {
                hAllowedDistance *= Magic.modSoulSpeed;
                data.keepfrictiontick = 60;
            }
            useSneakModifier = true;
            useBaseModifiers = true;
            friction = 0.0;
            // NOTE: Soulsand above ice: https://bugs.mojang.com/browse/MC-163952 henche why we don't enforce slower speed here.
        }
        
        // Slimeblock
        // Only enforce slower speed if the move is fully on ground
        else if (thisMove.from.onSlimeBlock && thisMove.to.onSlimeBlock) {
            tags.add("hslimeblock");
            hAllowedDistance = Magic.modSlime * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            useSneakModifier = true; 
            useBaseModifiers = true;
            friction = 0.0;
        }

        // Honeyblock
        else if (thisMove.from.onHoneyBlock) {
            tags.add("hhoneyblock");
            hAllowedDistance = modHoneyBlock * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            useSneakModifier = true;
            useBaseModifiers = true;
            friction = 0.0; 
        }

        // Stairs
        // TODO: Ensure higher speed only for flight of stairs, rather
        else if (thisMove.from.aboveStairs) {
            tags.add("hstairs");
            useBaseModifiers = true;
            useSneakModifier = true;
            hAllowedDistance = modStairs * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            friction = 0.0;
            if (!Double.isInfinite(mcAccess.getHandle().getFasterMovementAmplifier(player))) hAllowedDistance *= 0.88;
        }

        // NoSlow packet (detection, not a deterministic limit)
        else if (data.isHackingRI && !pData.hasPermission(Permissions.MOVING_SURVIVALFLY_BLOCKING, player)) {
            tags.add("usingitem(cancel)");
            data.isHackingRI = false;
            hAllowedDistance = 0.0;
            friction = 0.0;
            useBaseModifiers = false;
        }

        // Collision tolerance for entities (1.9+)
        else if (ServerIsAtLeast1_9 && CollisionUtil.isCollidingWithEntities(player, true) 
                && hAllowedDistance < 0.35 && data.liftOffEnvelope == LiftOffEnvelope.NORMAL) {
            tags.add("hcollision");
            hAllowedDistance = Magic.modCollision * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
            useBaseModifiers = true;
            data.bunnyhopTick = 20;
            friction = 0.0;
        }

        // In liquid
        // Check all liquids (lava might demand even slower speed though).
        else if (thisMove.from.inLiquid && thisMove.to.inLiquid && !data.isHalfGroundHalfWater) {
            tags.add("hliquid");
            hAllowedDistance = Bridge1_13.isSwimming(player) ? Magic.modSwim[1] : Magic.modSwim[0] * thisMove.walkSpeed * cc.survivalFlySwimmingSpeed / 100D;
            useBaseModifiers = false;
            useSneakModifier = true;
            if (sfDirty) friction = 0.0;
            
            // Account for all water-related enchants
            if (thisMove.from.inWater || !thisMove.from.inLava) { 
                final int level = BridgeEnchant.getDepthStriderLevel(player);
                if (level > 0) {
                    // Speed effect, attribute will affect to water movement whenever you has DepthStrider enchant.
                    useBaseModifiers = true;
                    useBaseModifiersSprint = true;
                    hAllowedDistance *= Magic.modDepthStrider[level];
                    // Modifiers: Most speed seems to be reached on ground, but couldn't nail down.
                }

                if (!Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))) {
                    // TODO: Allow for faster swimming above water with Dolhphins Grace
                    hAllowedDistance *= Magic.modDolphinsGrace;
                    if (level > 1) hAllowedDistance *= 1.0 + 0.07 * level;
                }

                if (data.liqtick < 5 && lastMove.toIsValid) {
                    if (!lastMove.from.inLiquid) {
                        if (lastMove.hDistance * 0.92 > thisMove.hDistance) {
                            hAllowedDistance = lastMove.hDistance * 0.92;
                        }
                    } 
                    else if (lastMove.hAllowedDistance * 0.92 > thisMove.hDistance) {
                        hAllowedDistance = lastMove.hAllowedDistance * 0.92;
                    }
                }
            }
        }

        // Speed restriction for leaving a liquid
        // TODO: Still check with velocity?
        // Instead of using an ID, switch to Magic.splashMove() ?
        else if (!data.isHalfGroundHalfWater && !sfDirty && !pData.hasPermission(Permissions.MOVING_SURVIVALFLY_WATERWALK, player) 
                && ((thisMove.from.inLiquid && !thisMove.to.inLiquid) || data.surfaceId == 1) 
                && data.liftOffEnvelope.name().startsWith("LIMIT")
                ) {
            tags.add("hsurface");
            hAllowedDistance = Bridge1_13.isSwimming(player) ? Magic.modSwim[1] : Magic.modSwim[0] * thisMove.walkSpeed * Magic.modSurface[0] * cc.survivalFlySwimmingSpeed / 100D;
            useBaseModifiersSprint = false;
            friction = 0.0;
            final int level = BridgeEnchant.getDepthStriderLevel(player);
            
            if (level > 0 && data.surfaceId == 0) {
               // Speed effect, attribute will affect to water movement whenever you has DepthStrider enchant.
               useBaseModifiers = true;
               useBaseModifiersSprint = true;
               friction = data.lastFrictionHorizontal;
               hAllowedDistance *= Magic.modDepthStrider[level];
            }

            if (!Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))) {
                hAllowedDistance *= Magic.modDolphinsGrace;
                if (level > 1) hAllowedDistance *= 1.0 + 0.07 * level;
            }

            if (data.surfaceId == 1) hAllowedDistance *= Magic.modSurface[1];
            // This movement has left liquid, set the Id to keep enforcing the speed limit
            data.surfaceId = 1;
            final int blockData = from.getData(from.getBlockX(), from.getBlockY(), from.getBlockZ());
            final int blockUnderData = from.getData(from.getBlockX(), from.getBlockY() -1, from.getBlockZ());

            // Reset the Id for these conditions.
            if (blockData > 3 || blockUnderData > 3 || data.isdownstream) {
                data.surfaceId = 0;
                hAllowedDistance = thisMove.walkSpeed * cc.survivalFlySwimmingSpeed / 100D;
                data.isdownstream = false;
            }
        }

        // Sneaking
        // TODO: !sfDirty is very coarse, should use friction instead.
        // TODO: Attribute modifiers can count in here, e.g. +0.5 (+ 50% doesn't seem to pose a problem, neither speed effect 2).
        // TODO: Test how to go without checking from on ground (ensure sneaking speed in air as well)
        else if (!sfDirty && thisMove.from.onGround && actuallySneaking 
                && (!checkPermissions || !pData.hasPermission(Permissions.MOVING_SURVIVALFLY_SNEAKING, player))
                ) {
            tags.add("sneaking");
            hAllowedDistance = Magic.modSneak * thisMove.walkSpeed * cc.survivalFlySneakingSpeed / 100D;
            useBaseModifiers = true;
            friction = 0.0; // Ensure friction can't be used to speed.

            if (!Double.isInfinite(mcAccess.getHandle().getFasterMovementAmplifier(player))) {
                hAllowedDistance *= 0.88;
                useBaseModifiersSprint = true;
            }
        }

        // Using items
        // TODO: !sfDirty is very coarse, should use friction instead.
        else if (!sfDirty && isBlockingOrUsing && (thisMove.from.onGround || data.noslowhop > 0 || player.isBlocking())
                && (!checkPermissions || !pData.hasPermission(Permissions.MOVING_SURVIVALFLY_BLOCKING, player)) 
                && data.liftOffEnvelope == LiftOffEnvelope.NORMAL) {
            tags.add("usingitem");
            if (thisMove.from.onGround) {
                // Jump/left ground
                if (!thisMove.to.onGround) {
                    final double speedAmplifier = mcAccess.getHandle().getFasterMovementAmplifier(player);
                    hAllowedDistance = (lastMove.hDistance > 0.23 ? 0.4 : 0.23 + (ServerIsAtLeast1_13 ? 0.155 : 0.0)) +
                                        0.02 * (Double.isInfinite(speedAmplifier) ? 0 : speedAmplifier + 1.0);
                    hAllowedDistance *= cc.survivalFlyBlockingSpeed / 100D;
                    data.noslowhop = 1;
                }
                // OnGround
                else {
                    // TODO: Need testing
                    if (lastMove.toIsValid && lastMove.hDistance > 0.0) 
                       hAllowedDistance = data.noslowhop < 7 ?
                                        // 0.6 for old vers, 0.621 for 1.13+
                                        (lastMove.hAllowedDistance * (0.63 + 0.052 * ++data.noslowhop)) : lastMove.hAllowedDistance;
                    // Failed or no hDistance in last move, return to default speed
                    else hAllowedDistance = Magic.modBlock * thisMove.walkSpeed * cc.survivalFlyBlockingSpeed / 100D;
                }
            }
            else if (data.noslowhop > 0) {
                if (data.noslowhop == 1 && lastMove.toIsValid) {
                    // Second move after jump, high decay
                    hAllowedDistance = lastMove.hAllowedDistance * 0.6 * cc.survivalFlyBlockingSpeed / 100D;
                    // Fake data, prevent too much friction after slow - rejump
                    data.noslowhop = 4;
                }
                // Air friction
                else hAllowedDistance = lastMove.hAllowedDistance * 0.96 * cc.survivalFlyBlockingSpeed / 100D;
            }
            else if (player.isBlocking() && lastMove.toIsValid) {
                // Air friction
                hAllowedDistance = lastMove.hAllowedDistance * 0.96 * cc.survivalFlyBlockingSpeed / 100D;
                // Fake data for air blocking
                data.noslowhop = 2;
            }
            // Check if too small horizontal last move allowed
            // 0.063 for old vers, 0.08 for 1.13+
            hAllowedDistance = Math.max(hAllowedDistance, 0.08);
            friction = 0.0; // Ensure friction can't be used to speed.
            useBaseModifiers = true;
            useBaseModifiersSprint = false;
        }
        // Fallback to the default speed
        else {
            useBaseModifiers = true;
            // Landing on ground allows a slightly faster move.
            // Allow players to retain momentum for a bit if landing after bunnyslide
            if (!thisMove.from.onGround && thisMove.to.onGround) {
                data.bunnyhopTick = ServerIsAtLeast1_13 ? (lastMove.bunnySlide ? 27 : 6) : (lastMove.bunnySlide ? 27 : 3);
                hAllowedDistance = 1.14 * thisMove.walkSpeed * cc.survivalFlySprintingSpeed / 100D;
                tags.add("sprintTo");
            }
            // Grace ticks after landing.
            else if (data.bunnyhopTick > 0) {
                hAllowedDistance = modHopSprint * thisMove.walkSpeed * cc.survivalFlySprintingSpeed / 100D;
                // TODO: remove...
                if (snowFix && data.bunnyhopTick > 5) hAllowedDistance *= 1.6;
                tags.add("sprinthop");
            }
            // Ground -> ground or Air -> air (Sprinting speed is already included)
            else {
                hAllowedDistance = thisMove.walkSpeed * cc.survivalFlySprintingSpeed / 100D; 
                tags.add("walkspeed");              
            }
            // Don't keep friction unless is on ice.
            if (!Magic.touchedIce(thisMove)) friction = 0.0;
        }



        /////////////////////////////////////////////////
        // Apply modifiers (sprinting, attributes, ...)//
        /////////////////////////////////////////////////
        if (useBaseModifiers) {
            if (useBaseModifiersSprint && sprinting) {
                hAllowedDistance *= data.multSprinting;
            }
            // Note: Attributes count in slowness potions, thus leaving out isn't possible.
            final double attrMod = attributeAccess.getHandle().getSpeedAttributeMultiplier(player);
            if (attrMod == Double.MAX_VALUE) {
                // TODO: Slowness potion.
                // Count in speed potions.
                final double speedAmplifier = mcAccess.getHandle().getFasterMovementAmplifier(player);
                if (!Double.isInfinite(speedAmplifier) && useBaseModifiersSprint) {
                    hAllowedDistance *= 1.0D + 0.2D * (speedAmplifier + 1);
                }
            }
            else {
                hAllowedDistance *= attrMod;
                // TODO: Consider getting modifiers from items, calculate with classic means (or iterate over all modifiers).
                // Hack for allow sprint-jumping with slowness.
                if (sprinting && hAllowedDistance < 0.29 && cc.sfSlownessSprintHack && 
                    (
                        // TODO: Test/balance thresholds (walkSpeed, attrMod).
                        player.hasPotionEffect(PotionEffectType.SLOW)
                        || data.walkSpeed < Magic.DEFAULT_WALKSPEED
                        || attrMod < 1.0
                    )) {
                    // TODO: Should restrict further by yDistance, ground and other (jumping only).
                    // TODO: Restrict to not in water (depth strider)?
                    hAllowedDistance = slownessSprintHack(player, hAllowedDistance);
                }
                //useBaseModifiersSprint = false mean not apply speed effect in it 
                if (!useBaseModifiersSprint) {
                    final double speedAmplifier = mcAccess.getHandle().getFasterMovementAmplifier(player);
                    if (!Double.isInfinite(speedAmplifier)) {
                        hAllowedDistance /= attrMod;
                        hAllowedDistance *= attrMod - 0.2D * (speedAmplifier + 1);
                    }
                }
            }
        }
        


        ////////////////////////////////////////////////////////////////////////////////////
        // Apply other modifiers that have to be set in hAllowedDistanceBase (not final) //
        ///////////////////////////////////////////////////////////////////////////////////
        // TODO: Reset friction on too big change of direction?
        // TODO Move the collision tolerance here?
        // Account for flowing liquids (only if needed).
        if (thisMove.downStream && thisMove.hDistance > thisMove.walkSpeed * Magic.modSwim[0] 
            && thisMove.from.inLiquid) {
            hAllowedDistance *= Magic.modDownStream;
        }
        
        // Apply sneaking speed for other modifiers.
        if (useSneakModifier && actuallySneaking) {
            hAllowedDistance *= 0.682;
        }
        
        // Jumping on ice with headobstructed and trapdoor underneath, we can't detect players jumping so just give them a higher speed.
        if (data.iceFrictionTick > 0) {
            hAllowedDistance *= (data.iceFrictionTick > 9) ? Magic.modIce : (1.0 + 0.025 * data.iceFrictionTick);
        }

        // Speeding bypass permission (can be combined with other bypasses).
        if (checkPermissions && pData.hasPermission(Permissions.MOVING_SURVIVALFLY_SPEEDING, player)) {
            hAllowedDistance *= cc.survivalFlySpeedingSpeed / 100D;
        }

        // Base speed is set.
        thisMove.hAllowedDistanceBase = hAllowedDistance;



        /////////////////////////////////////////////////////////////////////
        // Set the finally allowed speed, accounting for friction and more //
        ////////////////////////////////////////////////////////////////////
        // Friction mechanics (next move).
        // Move is within lift-off/burst envelope, allow next time.
        // TODO: This probably is the wrong place (+ bunny, + buffer)?
        if (thisMove.hDistance <= hAllowedDistance) {
            data.nextFrictionHorizontal = 1.0;
        }

        // Soul speed workaround (friction)
        if (data.keepfrictiontick > 0) {
            if (!BridgeEnchant.hasSoulSpeed(player)) {
                data.keepfrictiontick = 0;
            } 
            else if (lastMove.toIsValid) {
                hAllowedDistance = Math.max(hAllowedDistance, lastMove.hAllowedDistance * 0.96);
            }
        }

        // Friction or not (this move).
        // TODO: Invalidation mechanics.
        // TODO: Friction model for high speeds?
        if (lastMove.toIsValid && friction > 0.0) {
            tags.add("hfrict");
            hAllowedDistance = Math.max(hAllowedDistance, lastMove.hDistance * friction);
        }
        
        // The final allowed speed is set.
        thisMove.hAllowedDistance = hAllowedDistance;
        return thisMove.hAllowedDistance;
    }


    /**
     * Return a 'corrected' allowed horizontal speed. Call only if the player
     * has a SLOW effect.
     * 
     * @param player
     * @param hAllowedDistance
     * @return
     */
    private double slownessSprintHack(final Player player, final double hAllowedDistance) {
        // TODO: Certainly wrong for items with speed modifier (see above: calculate the classic way?).
        // Simple: up to high levels they can stay close, with a couple of hops until max base speed. 
        return 0.29;
    }


    /**
     * Access method from outside.
     * @param player
     * @return
     */
    public boolean isReallySneaking(final Player player) {
        return reallySneaking.contains(player.getName());
    }


    /**
     * Core y-distance checks for in-air movement (may include air -> other).
     * See InAirRules to check (most of) the exemption rules.
     *
     * @return
     */
    private double[] vDistAir(final long now, final Player player, final PlayerLocation from, 
                              final boolean fromOnGround, final boolean resetFrom, final PlayerLocation to, 
                              final boolean toOnGround, final boolean resetTo, 
                              final double hDistance, final double yDistance, 
                              final int multiMoveCount, final PlayerMoveData lastMove, 
                              final MovingData data, final MovingConfig cc, final IPlayerData pData) {

        // TODO: Other edge cases?
        // TODO: Fix negative jump boosts.
        // TODO/SOON: Throw in stairs handling, no Ground_Height flag (!) (estimate distance, add workarounds where/if needed)
        //            (Aim is to fix the lowjump detection (see notes))
        double vAllowedDistance          = 0.0;
        double vDistanceAboveLimit       = 0.0;
        final PlayerMoveData thisMove    = data.playerMoves.getCurrentMove();
        final double yDistChange         = lastMove.toIsValid ? yDistance - lastMove.yDistance : Double.MAX_VALUE; // Change seen from last yDistance.
        final double maxJumpGain         = data.liftOffEnvelope.getMaxJumpGain(data.jumpAmplifier);
        final int maxJumpPhase           = data.liftOffEnvelope.getMaxJumpPhase(data.jumpAmplifier);
        final double jumpGainMargin      = 0.005; // TODO: Model differently, workarounds where needed. 0.05 interferes with max height vs. velocity (<= 0.47 gain).
        final boolean strictVdistRel;


        /////////////////////////////////////////////////
        // Estimate the allowed yDistance              //
        ////////////////////////////////////////////////
        // Less headache: Always allow falling. 
        if (lastMove.toIsValid && Magic.fallingEnvelope(yDistance, lastMove.yDistance, data.lastFrictionVertical, 0.0)) {
            vAllowedDistance = lastMove.yDistance * data.lastFrictionVertical - Magic.GRAVITY_MIN; // Upper bound.
            strictVdistRel = true;
        }
        else if (resetFrom || thisMove.touchedGroundWorkaround) {

            // TODO: More concise conditions? Some workaround may allow more.
            if (toOnGround) {

                // Hack for boats (coarse: allows minecarts too): allow staying on the entity
                if (yDistance > cc.sfStepHeight && yDistance - cc.sfStepHeight < 0.00000003 
                    && to.isOnGroundDueToStandingOnAnEntity()) {
                    vAllowedDistance = yDistance;
                }
                else {
                    vAllowedDistance = Math.max(cc.sfStepHeight, maxJumpGain + jumpGainMargin);
                    thisMove.allowstep = true;
                    thisMove.allowjump = true;
                }
            }
            else {

                // Code duplication with the absolute limit below.
                if (yDistance < 0.0 || yDistance > cc.sfStepHeight || !tags.contains("lostground_couldstep")) {
                    vAllowedDistance = maxJumpGain + jumpGainMargin;
                    thisMove.allowjump = true;
                }
                else vAllowedDistance = yDistance;
            }
            strictVdistRel = false;
        }
        else if (lastMove.toIsValid) {

            if (lastMove.yDistance >= -Math.max(Magic.GRAVITY_MAX / 2.0, 1.3 * Math.abs(yDistance)) 
                && lastMove.yDistance <= 0.0 
                && (lastMove.touchedGround || lastMove.to.extraPropertiesValid && lastMove.to.resetCond)) {

                if (resetTo) {
                    vAllowedDistance = cc.sfStepHeight;
                    thisMove.allowstep = true;
                }
                else {
                    vAllowedDistance = maxJumpGain + jumpGainMargin;
                    thisMove.allowjump = true;
                } // TODO: Needs more precise confinement + setting set back or distance to ground or estYDist.
                strictVdistRel = false;
            }
            else {
                // Friction.
                // TODO: data.lastFrictionVertical (see above).
                vAllowedDistance = lastMove.yDistance * data.lastFrictionVertical - Magic.GRAVITY_ODD; // Upper bound.
                strictVdistRel = true;
            }
        }
        // Teleport/join/respawn.
        else {
            tags.add(lastMove.valid ? "data_reset" : "data_missing");
            
            // Allow falling.
            if (thisMove.yDistance > -(Magic.GRAVITY_MAX + Magic.GRAVITY_SPAN) && yDistance < 0.0) {
                vAllowedDistance = yDistance; 
                // TODO: Test code below: would be best to not allow arbitrary distance. At least enforce fricton.
                // vAllowedDistance = lastMove.yDistance * data.lastFrictionVertical - Magic.GRAVITY_MIN;
            }
            // Allow jumping.
            else if (thisMove.from.onGround || (lastMove.valid && lastMove.to.onGround)) {
                // TODO: Is (lastMove.valid && lastMove.to.onGround) safe?
                vAllowedDistance = maxJumpGain + jumpGainMargin;
                if (lastMove.to.onGround && vAllowedDistance < 0.1) vAllowedDistance = maxJumpGain + jumpGainMargin;
                // Allow stepping
                if (thisMove.to.onGround) vAllowedDistance = Math.max(cc.sfStepHeight, vAllowedDistance);
            }
            // Double arithmetics, moving up after join/teleport/respawn. Edge case in PaperMC/Spigot 1.7.10
            else if (Magic.skipPaper(thisMove, lastMove, data)) {
                vAllowedDistance = Magic.PAPER_DIST;
                tags.add("skip_paper");
            }
            // Do not allow any distance.
            else vAllowedDistance = 0.0;
            strictVdistRel = false;
        }


        ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // Compare yDistance to expected and search for an existing rule. Use velocity on violations, if nothing has been found. //
        //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        /** Relative vertical distance */
        boolean vDistRelVL = false;
        /** Expected difference from current to allowed */       
        final double yDistDiffEx          = yDistance - vAllowedDistance; 
        final boolean honeyBlockCollision = isCollideWithHB(from, to, data) && yDistance < -0.125 && yDistance > -0.128;
        final boolean GravityEffects      = InAirRules.oddJunction(from, to, yDistance, yDistChange, yDistDiffEx, maxJumpGain, resetTo, thisMove, lastMove, data, cc);
        final boolean TooBigMove          = InAirRules.outOfEnvelopeExemptions(yDistance, yDistDiffEx, lastMove, data, from, to, now, yDistChange, maxJumpGain, player, thisMove, resetTo);
        final boolean TooShortMove        = InAirRules.shortMoveExemptions(yDistance, yDistDiffEx, lastMove, data, from, to, now, strictVdistRel, maxJumpGain, vAllowedDistance, player, thisMove);
        final boolean TooFastFall         = InAirRules.fastFallExemptions(yDistance, yDistDiffEx, lastMove, data, from, to, now, strictVdistRel, yDistChange, resetTo, fromOnGround, toOnGround, maxJumpGain, player, thisMove, resetFrom);
        final boolean VEnvHack            = InAirRules.venvHacks(from, to, yDistance, yDistChange, thisMove, lastMove, data) && !resetFrom && !resetTo;
        final boolean TooBigMoveNoData    = InAirRules.outOfEnvelopeNoData(yDistance, from, to, thisMove, resetTo, data);

        // Quick invalidation for too much water envelope
        // Should have a look at isNextToGround margins (see above)
        if (!from.isInLiquid() && strictVdistRel 
            && data.liftOffEnvelope == LiftOffEnvelope.LIMIT_LIQUID
            && yDistance > 0.3 && yDistance > vAllowedDistance 
            && data.getOrUseVerticalVelocity(yDistance) == null) {
            thisMove.invalidate();
        }

        if (VEnvHack || yDistDiffEx <= 0.0 && yDistDiffEx > -Magic.GRAVITY_SPAN && data.ws.use(WRPT.W_M_SF_ACCEPTED_ENV)) {
            // Accepted envelopes first
        }
        // Upper bound violation: bigger move than expected
        else if (yDistDiffEx > 0.0) { 
            
            if (TooBigMove || TooBigMoveNoData || GravityEffects 
                || (lastMove.toIsValid && (honeyBlockCollision || isLanternUpper(to)))) {
                // Several types of odd in-air moves, mostly with gravity near its maximum, friction and medium change.
            }
            else vDistRelVL = true;
        } 
        // Smaller move than expected (yDistDiffEx <= 0.0 && yDistance >= 0.0)
        else if (yDistance >= 0.0) { 

            if (TooShortMove || GravityEffects || isLanternUpper(to)) {
                // Several types of odd in-air moves, mostly with gravity near its maximum, friction and medium change.
                // NOTE: TooShortMove does not require last move to be valid, GravityEffects does.
            }
            else vDistRelVL = true;
        }
        // Too fast fall (yDistDiffEx <= 0.0 && yDistance < 0.0)
        else { 

            if (TooFastFall || GravityEffects || isLanternUpper(to) || honeyBlockCollision) {
                // Several types of odd in-air moves, mostly with gravity near its maximum, friction and medium change.
                // NOTE: TooFastFall does not require last move to be valid, GravityEffects does.
            }
            else vDistRelVL = true;
        }

        
        // No match found (unexpected move): use velocity as compensation, if available.
        if (vDistRelVL) {
            if (data.getOrUseVerticalVelocity(yDistance) == null) {
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance - vAllowedDistance));
                tags.add("vdistrel");
            }
        }


        //////////////////////////////////////////////////////////////////////////////
        // Prevent players from moving further than the (absolute) setback distance.//
        //////////////////////////////////////////////////////////////////////////////
        // TODO: Maintain a value in data, adjusting to velocity?
        if (!pData.hasPermission(Permissions.MOVING_SURVIVALFLY_STEP, player) && yDistance > 0.0 
            && !data.isVelocityJumpPhase() && data.hasSetBack()) {
            
            final double vAllowedAbsoluteDistance = data.liftOffEnvelope.getMaxJumpHeight(data.jumpAmplifier);
            final double totalVDistViolation      = to.getY() - data.getSetBackY() - vAllowedAbsoluteDistance;
            if (totalVDistViolation > 0.0) {
        
                if (InAirRules.vDistSBExemptions(toOnGround, thisMove, lastMove, data, cc, now, player, 
                                                 totalVDistViolation, yDistance, fromOnGround)) {
                    // Skip
                }
                else if (yDistance <= cc.sfStepHeight && thisMove.touchedGroundWorkaround 
                        && tags.contains("lostground_couldstep")) {
                    // Skip if the player could step up by lostground_couldstep.
                }
                // Attempt to use velocity.
                else if (data.getOrUseVerticalVelocity(yDistance) == null) {
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.max(totalVDistViolation, 0.4));
                    tags.add("vdistsb");
                }
            }
        }


        ///////////////////////////////////////////////////////////////////////////////////////
        // Air-stay-time: prevent players from ascending further than the maximum jump phase.//
        ///////////////////////////////////////////////////////////////////////////////////////
        // TODO: Consider making the leniency yDist configurable...
        if (!VEnvHack && data.sfJumpPhase > maxJumpPhase && !data.isVelocityJumpPhase()) {

            if (yDistance < 0.05) { // Leniency
                // Ignore falling, and let accounting deal with it.
            }
            else if (resetFrom) {
                // Ignore bunny etc.
            }
            // Violation (Too high jumping or step).
            else if (data.getOrUseVerticalVelocity(yDistance) == null) {
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.max(yDistance, 0.15));
                tags.add("maxphase");
            }
        }
        

        //////////////////////////////////////////////////////////////////////////////////
        // Check on change of Y direction: includes lowjump detection and airjump check //
        //////////////////////////////////////////////////////////////////////////////////
        final Material blockBelow = from.getTypeId(from.getBlockX(), Location.locToBlock(from.getY() - 1.0), from.getBlockZ());
        final boolean InAirPhase = !VEnvHack && !resetFrom && !resetTo;
        final boolean ChangedYDir = lastMove.toIsValid && lastMove.yDistance != yDistance
                                    && (yDistance <= 0.0 && lastMove.yDistance >= 0.0 
                                    || yDistance >= 0.0 && lastMove.yDistance <= 0.0); 

        if (InAirPhase && ChangedYDir) {

            // TODO: Does this account for velocity in a sufficient way?
            if (yDistance > 0.0) {
                // TODO: Clear active vertical velocity here ?
                // TODO: Demand consuming queued velocity for valid change (!).
                // Increase
                if (lastMove.touchedGround || lastMove.to.extraPropertiesValid && lastMove.to.resetCond) {
                    tags.add("ychinc");
                }
                else {
                    // Moving upwards after falling without having touched the ground.
                    if (data.bunnyhopDelay < 9 && !((lastMove.touchedGround || lastMove.from.onGroundOrResetCond)
                        && lastMove.yDistance == 0D) && data.getOrUseVerticalVelocity(yDistance) == null
                        && !isLanternUpper(to)) {
                        vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance));
                        tags.add("airjump");
                    }
                    else tags.add("ychincair");
                }
            }
            else {
                // Decrease
                tags.add("ychdec");
                // Detect low jumping.
                // TODO: sfDirty: Account for actual velocity (demands consuming queued for dir-change(!))!
                if (!data.sfLowJump && !data.sfNoLowJump && data.liftOffEnvelope == LiftOffEnvelope.NORMAL
                    && lastMove.toIsValid && lastMove.yDistance > 0.0 && !data.isVelocityJumpPhase()) {

                    final double setBackYDistance = from.getY() - data.getSetBackY();
                    double estimate = (data.jumpAmplifier > 0) ? 1.15 + (0.5 * aux.getJumpAmplifier(player)) : 1.15; // Estimate of minimal jump height.
                    // Only count it if the player has actually been jumping (higher than setback).
                    if (setBackYDistance > 0.0 && setBackYDistance < estimate) {

                        // Workaround for jumping up slopes:
                        // 1) When jumping next to a block, the player's hitbox will collide with it even though they are still asending.
                        //    This will cause a false positive as touching the ground also means resetting/updating the setback location.
                        //    Should rather take a look into the onGround logic, but not messing with that for now :)
                        // 2) Stairs are a different issue: the small gap in them is regarded as passable ground by NCP which means the setback location
                        //    will be reset/updated at the very top (1.0) when jumping up, instead of keeping it at the lower edge (0.5) [Note: removing the F_GROUND_HEIGHT
                        //    fixes the issue but will cause troubles with vDistrel]
                        // TODO: Test margins, could be more strict.
                        // TODO: Actual fix for stairs, then let's actually punish players for lowjumping (un-comment code below)
                        // TODO: Thinkable: feed the Improbable on too high occurance of low jumping
                        if ((from.isNextToGround(0.15, 0.4) || to.isNextToGround(0.15, 0.4)) 
                            && Math.round(from.getY()) == Math.round(data.getSetBackY())
                            || thisMove.headObstructed 
                            || yDistance <= 0.0 && lastMove.headObstructed && lastMove.yDistance >= 0.0
                            // TODO: Jumping on the lower edge with off-centered (almost outside block) bounding box will still flag...
                            || BlockProperties.isStairs(blockBelow)) {
                            // Exempt.
                            tags.add("lowjump_skip");
                        }
                        else {
                            // vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance));
                            data.sfLowJump = true;
                        }
                    }
                } 
            }
        }
        

        ////////////////////////////////////////////////////////
        // The Vertical Accounting subcheck: gravity enforcer //
        ///////////////////////////////////////////////////////
        if (InAirPhase && cc.survivalFlyAccountingV) {

            // Currently only for "air" phases.
            if (isCollideWithHB(from, to, data) && thisMove.yDistance < 0.0 && thisMove.yDistance > -0.21) {
                data.vDistAcc.clear();
                data.vDistAcc.add((float)-0.2033);
            }
            else if (ChangedYDir && lastMove.yDistance > 0.0) { // lastMove.toIsValid is checked above. 
                // Change to descending phase.
                data.vDistAcc.clear();
                // Allow adding 0.
                data.vDistAcc.add((float) yDistance);
            }
            else if (thisMove.verVelUsed == null // Only skip if just used.
                    && !(isLanternUpper(to) || lastMove.from.inLiquid && Math.abs(yDistance) < 0.31 
                    || data.timeRiptiding + 1000 > now)) { 
                
                // Here yDistance can be negative and positive.
                data.vDistAcc.add((float) yDistance);
                final double accAboveLimit = verticalAccounting(yDistance, data.vDistAcc, tags, "vacc" + (data.isVelocityJumpPhase() ? "dirty" : ""));

                if (accAboveLimit > vDistanceAboveLimit) {
                    if (data.getOrUseVerticalVelocity(yDistance) == null) {
                        vDistanceAboveLimit = accAboveLimit;
                    }
                }         
            }
            // TODO: Just to exclude source of error, might be redundant.
            else data.vDistAcc.clear(); 
        } 


        // Join the lowjump tag
        if (data.sfLowJump) {
            tags.add("lowjump");
        }

        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }
    

    /**
     * Demand that with time the values decrease.<br>
     * The ActionAccumulator instance must have 3 buckets, bucket 1 is checked against
     * bucket 2, 0 is ignored. [Vertical accounting: applies to both falling and jumping]<br>
     * NOTE: This just checks and adds to tags, no change to acc.
     * 
     * @param yDistance
     * @param acc
     * @param tags
     * @param tag Tag to be added in case of a violation of this sub-check.
     * @return A violation value > 0.001, to be interpreted like a moving violation.
     */
    private static final double verticalAccounting(final double yDistance, 
                                                   final ActionAccumulator acc, final ArrayList<String> tags, 
                                                   final String tag) {
        // TODO: Add air friction and do it per move anyway !?
        final int count0 = acc.bucketCount(0);
        if (count0 > 0) {
            final int count1 = acc.bucketCount(1);
            if (count1 > 0) {
                final int cap = acc.bucketCapacity();
                final float sc0;
                sc0 = (count0 == cap) ? acc.bucketScore(0) : 
                                        // Catch extreme changes quick.
                                        acc.bucketScore(0) * (float) cap / (float) count0 - Magic.GRAVITY_VACC * (float) (cap - count0);
                final float sc1 = acc.bucketScore(1);
                if (sc0 > sc1 - 3.0 * Magic.GRAVITY_VACC) {
                    // TODO: Velocity downwards fails here !!!
                    if (yDistance <= -1.05 && sc1 < -8.0 && sc0 < -8.0) { // (aDiff < Math.abs(yDistance) || sc2 < - 10.0f)) {
                        // High falling speeds may pass.
                        tags.add(tag + "grace");
                        return 0.0;
                    }
                    tags.add(tag);
                    return sc0 - (sc1 - 3.0 * Magic.GRAVITY_VACC);
                }
            }
        }
        return 0.0;
    }


    /**
     * After-failure checks for horizontal distance.
     * Buffer, velocity, bunnyhop, block move and reset-item.
     * 
     * @param player
     * @param from
     * @param to
     * @param hAllowedDistance
     * @param hDistanceAboveLimit
     * @param sprinting
     * @param thisMove
     * @param lastMove
     * @param data
     * @param cc
     * @param skipPermChecks
     * @return hAllowedDistance, hDistanceAboveLimit, hFreedom
     */
    private double[] hDistAfterFailure(final Player player, 
                                       final PlayerLocation from, final PlayerLocation to, 
                                       double hAllowedDistance, double hDistanceAboveLimit, final boolean sprinting, 
                                       final PlayerMoveData thisMove, final PlayerMoveData lastMove, 
                                       final MovingData data, final MovingConfig cc, final IPlayerData pData, 
                                       final boolean skipPermChecks) {

        // TODO: Still not entirely sure about this checking order.
        // TODO: Would quick returns make sense for hDistanceAfterFailure == 0.0?
        // Strictly speaking, bunnyhopping backwards is not possible, so we should reset the bhop model in such case.
        // However, we'd need a better "ismovingbackwards" model first tho, as the current one in TrigUtil is unreliable.

        // 1: Attempt to reset item on NoSlow Violation, if set so in the configuration.
        if (cc.survivalFlyResetItem && hDistanceAboveLimit > 0.0 && data.sfHorizontalBuffer <= 0.5 && tags.contains("usingitem")) {
            tags.add("itemreset");
            // Handle through nms
            if (mcAccess.getHandle().resetActiveItem(player)) {
                data.isusingitem = false;
                pData.requestUpdateInventory();
            }
            // Off hand (non nms)
            else if (Bridge1_9.hasGetItemInOffHand() && data.offhanduse) {
                ItemStack stack = Bridge1_9.getItemInOffHand(player);
                if (stack != null) {
                    if (ServerIsAtLeast1_13) {
                        if (player.isHandRaised()) {
                            // Does nothing
                        }
                        // False positive
                        else data.isusingitem = false;
                    } 
                    else {
                        player.getInventory().setItemInOffHand(stack);
                        data.isusingitem = false;
                    }
                }
            }
            // Main hand (non nms)
            else if (!data.offhanduse) {
                ItemStack stack = Bridge1_9.getItemInMainHand(player);
                if (ServerIsAtLeast1_13) {
                    if (player.isHandRaised()) {
                        data.olditemslot = player.getInventory().getHeldItemSlot();
                        if (stack != null) player.setCooldown(stack.getType(), 10);
                        player.getInventory().setHeldItemSlot((data.olditemslot + 1) % 9);
                        data.changeslot = true;
                    }
                    // False positive
                    else data.isusingitem = false;
                } 
                else {
                    if (stack != null) {
                        Bridge1_9.setItemInMainHand(player, stack);
                    }
                }
                data.isusingitem = false;
            }
            if (!data.isusingitem) {
                hAllowedDistance = setAllowedhDist(player, sprinting, thisMove, data, cc, pData, from, true);
                hDistanceAboveLimit = thisMove.hDistance - hAllowedDistance;
            }
        }

        // 2: Test bunny early, because it applies often and destroys as little as possible. 1st call
        hDistanceAboveLimit = bunnyHop(from, to, player, hAllowedDistance, hDistanceAboveLimit, sprinting, thisMove, lastMove, data, cc);

        // 3: Check being moved by blocks.
        // 1.025 is a Magic value
        if (cc.trackBlockMove && hDistanceAboveLimit > 0.0 && hDistanceAboveLimit < 1.025) {
            // Push by 0.49-0.51 in one direction. Also observed 1.02.
            // TODO: Better also test if the per axis distance is equal to or exceeds hDistanceAboveLimit?
            // TODO: The minimum push value can be misleading (blocked by a block?)
            final double xDistance = to.getX() - from.getX();
            final double zDistance = to.getZ() - from.getZ();
            if (Math.abs(xDistance) > 0.485 && Math.abs(xDistance) < 1.025
                && from.matchBlockChange(blockChangeTracker, data.blockChangeRef, 
                                         xDistance < 0 ? Direction.X_NEG : Direction.X_POS, 0.05)
                ) {
                hAllowedDistance = thisMove.hDistance; // MAGIC
                hDistanceAboveLimit = 0.0;
            }
            else if (Math.abs(zDistance) > 0.485 && Math.abs(zDistance) < 1.025
                    && from.matchBlockChange(blockChangeTracker, data.blockChangeRef, 
                                             zDistance < 0 ? Direction.Z_NEG : Direction.Z_POS, 0.05)
                    ) {
                hAllowedDistance = thisMove.hDistance; // MAGIC
                hDistanceAboveLimit = 0.0;
            }
        }

        // 4: Check velocity.
        double hFreedom = 0.0; // Horizontal velocity used.
        if (hDistanceAboveLimit > 0.0) {
            hFreedom = data.getHorizontalFreedom();
            if (hFreedom < hDistanceAboveLimit) {
                // Use queued velocity if possible.
                hFreedom += data.useHorizontalVelocity(hDistanceAboveLimit - hFreedom);
            }
            if (hFreedom > 0.0) {
                tags.add("hvel");
                bufferUse = false; 
                // Reset bunnyhopTick?
                hDistanceAboveLimit = Math.max(0.0, hDistanceAboveLimit - hFreedom);

                if (hDistanceAboveLimit <= 0.0) {
                    data.combinedMediumHCount = 0;
                    data.combinedMediumHValue = 0.0;
                    tags.add("hvel_no_hacc");
                }
            }
        }

        // 5: Re-check for bunnyhopping if the hDistance is still above limit (2nd).
        if (hDistanceAboveLimit > 0.0) {
            hDistanceAboveLimit = bunnyHop(from, to, player, hAllowedDistance, hDistanceAboveLimit, sprinting, thisMove, lastMove, data, cc);
        }

        // 6: Finally, check for the Horizontal buffer if the hDistance is still above limit.
        if (hDistanceAboveLimit > 0.0 && data.sfHorizontalBuffer > 0.0 && bufferUse && !Magic.inAir(thisMove)) {
            tags.add("hbufuse");
            final double amount = Math.min(data.sfHorizontalBuffer, hDistanceAboveLimit);
            hDistanceAboveLimit -= amount;
            data.sfHorizontalBuffer = Math.max(0.0, data.sfHorizontalBuffer - amount); // Ensure we never end up below zero.
        }

        // Add the hspeed tag on violation.
        if (hDistanceAboveLimit > 0.0) {
            tags.add("hspeed");
        }
        return new double[]{hAllowedDistance, hDistanceAboveLimit, hFreedom};
    }


    /**
     * Test bunny hop / bunny fly. Does modify data only if 0.0 is returned.
     * @param from
     * @param to
     * @param player
     * @param hDistance
     * @param hAllowedDistance
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param sprinting
     * @param data
     * @return hDistanceAboveLimit
     */
    private double bunnyHop(final PlayerLocation from, final PlayerLocation to, final Player player,
                            final double hAllowedDistance, double hDistanceAboveLimit, final boolean sprinting, 
                            final PlayerMoveData thisMove, final PlayerMoveData lastMove, 
                            final MovingData data, final MovingConfig cc) {

        // TODO: A more state-machine like modeling (hop, slope, states, low-edge).
        /*         
         * TODO:  Head obstructed (ordinary) [X]
         *        Head obstructed and slime [X]
         *        Head obstructed and ice/blue ice [X]
         *        Bunnyhopping on slime [X]
         *        Head obstructed with lower blocks
         *        Bunnyhopping out of a 2-blocks high area, with ice especially this is major.
         */
        from.collectBlockFlags();
        to.collectBlockFlags();
        boolean allowHop = true;
        boolean double_bunny = false;
        final double hDistance = thisMove.hDistance;
        final double yDistance = thisMove.yDistance;
        final double baseSpeed = thisMove.hAllowedDistanceBase;
        final double lastBaseSpeed = lastMove.hAllowedDistanceBase;
        final boolean touchedIce = Magic.touchedIce(thisMove);

        ///////////////////////////////////////////////////////////////////
        // After hop checks ("bunnyfly" phase, special cases)            //
        ///////////////////////////////////////////////////////////////////
        if (lastMove.toIsValid && data.bunnyhopDelay > 0 && hDistance > baseSpeed) {
            allowHop = false; // Need to wait for the delay countdown to be at 0 before one is allowed to hop again :)
            thisMove.bunnyFly = true;
            thisMove.bunnySlide = data.bunnySlide;
            final int hopTime = bunnyHopMax - data.bunnyhopDelay;
            
            // Bunnyfly phase
            // Check "bunny fly" here, to not fall over sprint resetting on the way.
            // Lexicon: Bunnyfly= Phase that covers the whole delay period; from bunnyhop to ground.
            if (lastMove.hDistance > hDistance) { // Speed is decreasing due to friction.

                final double hDistDiff = lastMove.hDistance - hDistance;
                final double slopeFactor = data.bunnySlide ? (0.66 / data.bunnyhopDelay) : 0.66;
                // Slope phase (downwards, directly after hop but before friction). 
                // Ensure relative speed decrease vs. hop is met somehow.
                if (data.bunnyhopDelay == 9 && hDistDiff >= slopeFactor * (lastMove.hDistance - baseSpeed)) {
                    tags.add("bunnyslope");
                    hDistanceAboveLimit = 0.0;
                }
                // Bunny friction: very few air friction than ordinary.
                else if (Magic.bunnyFrictionEnvelope(hDistDiff, lastMove.hDistance, hDistanceAboveLimit, hDistance, baseSpeed)) {
                
                    // Demand that bunnyhop speed decreases properly over the whole bunnyfly time (max-delay count)
                    final double speedGain = data.bunnySlide ? (data.frictionAccel * data.bunnyhopDelay) : 0.0;
                    final double speedMultiplier = data.bunnyhopTick > 0 ? (1.09 + speedGain) : (1.255 + speedGain);
                    final double maxSpeed = baseSpeed * speedMultiplier;
                    final double allowedSpeed = maxSpeed * Math.pow(0.99, bunnyHopMax - data.bunnyhopDelay);
                    if (hDistance <= allowedSpeed) {
                        tags.add("bunnyfriction");
                        hDistanceAboveLimit = 0.0;
                    }
                    // Likely cheating: attempting to jump too long (increasing speed) despite still being in the friction envelope set by NCP
                    // Slowly update Improbable which each out-of-envelope friction phase (Note: this method is called twice in 
                    // hDistanceAfterFailure, so we need to feed lower weights in order to prevent too fast VL escalation)
                    //  else {
                    //      Improbable.feed(player, (float) 0.008, System.currentTimeMillis()); 
                    //      tags.add("improbablebunny");
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


            // Do allow hop for special cases.
            if (!allowHop) { 

                // 2x horizontal speed increase detection: right after a bunnyhop
                // TODO: Is this one still needed? Doesn't seem to apply, ever.
                // (Apparently it was originally observed with parkours)
                if (hDistance - lastMove.hDistance >= baseSpeed * 0.5 && hopTime == 1
                    && lastMove.yDistance >= -Magic.GRAVITY_MAX / 2.0 
                    && lastMove.yDistance <= 0.0 && yDistance >= 0.4 
                    && lastMove.touchedGround) {
                    tags.add("doublebunny");
                    allowHop = double_bunny = true;
                }
                // (Not documented, not sure what this workaround is used for/about)
                // TODO: Better reset delay in this case ?
                // TODO: Confine further ?
                else if (data.bunnyhopDelay <= 6 && (thisMove.from.onGround || thisMove.touchedGroundWorkaround)) {
                    tags.add("ediblebunny");
                    allowHop = true;
                }
                // Less tediousness: Always allow bunnyhop once touching the ground if acceleration is within bound.
                else if (thisMove.touchedGround && thisMove.headObstructed 
                        && hDistance / baseSpeed < (touchedIce ? 3.0 : 2.015)) {
                    tags.add("headbangbunny");
                    allowHop = true;
                    
                    if (data.combinedMediumHValue / (double) data.combinedMediumHCount < (touchedIce ? 4.0 : 1.8)) {   
                        data.combinedMediumHCount = 1;
                        data.combinedMediumHValue = Math.min(baseSpeed, hDistance);
                        tags.add("bunny_no_hacc");
                    }
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
            if (Magic.bunnyFrictionEnvelope(hDistDiff, lastMove.hDistance, hDistanceAboveLimit, hDistance, baseSpeed)) {
                //if (data.lastbunnyhopDelay == 8 && thisMove.from.onGround && !thisMove.to.onGround) {
                //    data.lastbunnyhopDelay++;
                //    tags.add("bunnyfriction(keep)"); // TODO: Never happen?
                //} else 

                if (hDistDiff < 0.01) {
                    // Allow the move
                    hDistanceAboveLimit = 0.0;
                    tags.add("lostfrict");
                    
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
        // TODO: Test bunny spike over all sorts of speeds + attributes.
        // NOTE: Using a lower multiplier for headObstructed because the speed increase given by the bump can sometimes be negligible
        //       causing the hDistance to never reach the first threshold (hopTickMultiplier * baseSpeed).
        //       Same logic for slippery blocks.
        // Observed: Strafing on ice can cause small speed gains as well, hence the lower multiplier. (Sometimes even by just 1.005...)
        final double headObstrMult = touchedIce ? 2.932 : 1.968; // TODO: Test, ice might be too much, or too little...Not our fault if MC decides to allow such speed here.
        final boolean iceSlimeOrHeadObstr = thisMove.headObstructed || Magic.touchedSlipperyBlock(thisMove, from); // These share the same multiplier.
        final double hopTickMultiplier = iceSlimeOrHeadObstr ? 1.0279 : (!lastMove.toIsValid || lastMove.hDistance == 0.0 && lastMove.yDistance == 0.0) ? 1.11 : 1.314;
        final double hopTickMultiplier2 = thisMove.headObstructed ? headObstrMult : data.bunnyhopTick > 0 ? (data.bunnyhopTick > 2 ? 1.76 : 1.96) : 2.15;
        final double hopTickMultiplier3 = thisMove.headObstructed ? headObstrMult : data.bunnyhopTick > 0 ? (data.bunnyhopTick > 2 ? 1.9 : 2.1) : 2.3;

        // Only if we allow a hop and the hDistance is higher than the allowed speed.
        if (allowHop && hDistance >= baseSpeed
            // 0: Acceleration envelope 
            && (hDistance > hopTickMultiplier * baseSpeed) && (hDistance < hopTickMultiplier2 * baseSpeed)
            || (yDistance > from.getyOnGround() || hDistance < hopTickMultiplier3 * baseSpeed) 
            && lastMove.toIsValid && hDistance > 1.314 * lastMove.hDistance && hDistance < 2.15 * lastMove.hDistance
            ) { 
            
            // Pre-condition: normal jumping envelope, not a lowjump or a noLowJump flag is set for thisMove
            if (data.liftOffEnvelope == LiftOffEnvelope.NORMAL && (!data.sfLowJump || data.sfNoLowJump)
                // 0: Y-distance envelope
                &&  (
                    // 1: Normal jumping.
                    yDistance > 0.0 && yDistance > data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier) - Magic.GRAVITY_SPAN
                    // 1: Too short with head obstructed.
                    || thisMove.headObstructed || lastMove.toIsValid && lastMove.headObstructed
                    // 1: Hop without y distance increase at moderate h-speed (Legacy, still needed?)
                    || yDistance >= 0.0 && (cc.sfGroundHop || yDistance == 0.0 && !lastMove.touchedGroundWorkaround && !lastMove.from.onGround)
                    && baseSpeed > 0.0 && hDistance / baseSpeed < 1.5 && (hDistance / lastMove.hDistance < 1.35 || hDistance / baseSpeed < 1.35)
                    // 1: Odd bounce bunny: player has landed on ground with negative yDistance left.
                    || yDistance < 0.0 && Math.abs(yDistance - lastMove.yDistance) > 0.0 && Math.abs(yDistance - lastMove.yDistance) <= 0.195
                    && (from.getBlockFlags() & BlockProperties.F_BOUNCE25) != 0 && (to.getBlockFlags() & BlockProperties.F_BOUNCE25) != 0 
                )
                // 0: Ground + jump phase conditions.
                && (
                    // 1: Ordinary/obvious lift-off.
                    data.sfJumpPhase == 0 && thisMove.from.onGround 
                    // 1: Touched ground somehow. (LostGround)
                    || data.sfJumpPhase <= 1 && (thisMove.touchedGroundWorkaround || lastMove.touchedGround && !lastMove.bunnyHop) 
                    // 1: Double bunny.
                    || double_bunny
                    // 1: Always allow bunnyhop with head obstructed, provided the player has touched the ground
                    || thisMove.headObstructed && thisMove.touchedGround && data.sfJumpPhase <= 3
                    // Bounce bunny: from air > to ground -> immediately in air
                    || !thisMove.from.onGround && thisMove.to.onGround && (to.getBlockFlags() & BlockProperties.F_BOUNCE25) != 0 
                    && !lastMove.touchedGround && !lastMove.bunnyHop && data.sfJumpPhase <= 12 
                )
                // 0: Can't bunnyhop if in reset condition (climbable,water,web,bushes,powder snow)
                && (!from.isResetCond() && !to.isResetCond() || data.isHalfGroundHalfWater || !(data.iceFrictionTick > 0)) // TODO: !to.isResetCond() should be reviewed.
                ) {

                // Set the maximum delay before the player will be allowed to bunnyhop again. Bunnyfly starts.
                // Bunnyhopping on soul sand results in shorter jump. Shorten bunnyfly?
                data.bunnyhopDelay = bunnyHopMax;
                data.bunnySlide = Magic.touchedSlipperyBlock(thisMove, from);
                data.frictionAccel = getFrictionAccel(thisMove, from);
                hDistanceAboveLimit = 0.0;
                thisMove.bunnyHop = true;
                tags.add("bunnyhop");
            }
            // This move reached at least the bunnyhop acceleration envelopes.
            else tags.add("bunnyenv");
        }
        return hDistanceAboveLimit;
    }
    

    /**
     * Return the amount to add for increasing horizontal bunny friction speed during bunnyfly.
     * @param thisMove
     * @return
     */
    private double getFrictionAccel(final PlayerMoveData thisMove, final PlayerLocation from) {
        if (Magic.touchedIce(thisMove)) return 0.1167;
        else if (Magic.touchedBouncyBlock(thisMove, from)) return 0.1068;
        else return 0.0;
    }


    /**
     * Legitimate move: increase horizontal buffer somehow.
     * @param hDistance
     * @param amount Positive amount.
     * @param data
     */
    private void hBufRegain(final double hDistance, final double amount, final MovingData data, final MovingConfig cc) {
        /*
         * TODO: Consider different concepts: 
         *          - full resetting with harder conditions.
         *          - maximum regain amount.
         *          - reset or regain only every x blocks h distance.
         */
        // TODO: Confine general conditions for buffer regain further (regain in air, whatever)?
        data.sfHorizontalBuffer = Math.min(cc.hBufMax, data.sfHorizontalBuffer + amount);
    }


    /**
     * Inside liquids vertical speed checking. setFrictionJumpPhase must be set
     * externally.
     * Includes limit for waterlogged blocks (1.13+)
     * 
     * @param from
     * @param to
     * @param toOnGround
     * @param yDistance
     * @param data
     * @return vAllowedDistance, vDistanceAboveLimit
     */
    private double[] vDistLiquid(final PlayerMoveData thisMove, final PlayerLocation from, final PlayerLocation to, 
                                 final boolean toOnGround, final double yDistance, final PlayerMoveData lastMove, 
                                 final MovingData data, final Player player, final MovingConfig cc) {

        data.sfNoLowJump = true;
        final long now = System.currentTimeMillis();
        final double yDistAbs = Math.abs(yDistance);
        final double baseSpeed = thisMove.from.onGround ? Magic.swimBaseSpeedV(Bridge1_13.isSwimming(player)) + 0.1 
                                : Magic.swimBaseSpeedV(Bridge1_13.isSwimming(player));
        
        // TODO: Later also cover things like a sudden stop.
        // Minimal speed.
        if (yDistAbs <= baseSpeed) {
            return new double[]{baseSpeed, 0.0};
        }
        
        // Vertical checking for waterlogged blocks
        if (from.isOnGround() && !BlockProperties.isLiquid(from.getTypeIdAbove())
            && (from.isInWaterLogged() || data.isHalfGroundHalfWater)) {
            data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
            final double minJumpGain = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier);
            // Allow stepping.
            final boolean step = (toOnGround || thisMove.to.resetCond) && yDistance > minJumpGain && yDistance <= cc.sfStepHeight;
            final double vAllowedDistance = step ? cc.sfStepHeight : minJumpGain;
            tags.add("liquidground");
            return new double[]{vAllowedDistance, yDistance - vAllowedDistance};
        }

        // Friction envelope (allow any kind of slow down).
        final double frictDist = lastMove.toIsValid ? Math.abs(lastMove.yDistance) * data.lastFrictionVertical : baseSpeed; // Bounds differ with sign.
        if (lastMove.toIsValid) {
            if (lastMove.yDistance < 0.0 && yDistance < 0.0 && yDistAbs < frictDist + Magic.GRAVITY_MAX + Magic.GRAVITY_SPAN) {
                return new double[]{-frictDist - Magic.GRAVITY_MAX - Magic.GRAVITY_SPAN, 0.0};
            }
            if (lastMove.yDistance > 0.0 && yDistance > 0.0 && yDistance < frictDist - Magic.GRAVITY_SPAN) {
                return new double[]{frictDist - Magic.GRAVITY_SPAN, 0.0};
            }
            // Jump out water near edge ground
            if (lastMove.yDistance < -0.5 && yDistance > 0.4 && yDistance < frictDist - Magic.GRAVITY_MAX && from.isOnGround(0.6)) {
                return new double[]{frictDist - Magic.GRAVITY_MAX, 0.0};
            }
            // ("== 0.0" is covered by the minimal speed check above.)
        }

        // Workarounds for special cases.
        final Double wRes = InLiquidRules.liquidWorkarounds(from, to, baseSpeed, frictDist, lastMove, data);
        if (wRes != null) {
            return new double[]{wRes, 0.0};
        }
        // Try to use velocity for compensation.
        else if (data.getOrUseVerticalVelocity(yDistance) != null
            // TODO: Set magic speeds!
            || (from.getBlockFlags() & BlockProperties.F_BUBBLECOLUMN) != 0) {
            return new double[]{yDistance, 0.0};
        }

        // At this point a violation.
        tags.add(yDistance < 0.0 ? "swimdown" : "swimup");
        final double vl1 = yDistAbs - baseSpeed;
        final double vl2 = Math.abs(yDistAbs - frictDist - (yDistance < 0.0 ? Magic.GRAVITY_MAX + Magic.GRAVITY_SPAN : Magic.GRAVITY_MIN));
        if (vl1 <= vl2) return new double[]{yDistance < 0.0 ? -baseSpeed : baseSpeed, vl1};
        else return new double[]{yDistance < 0.0 ? -frictDist - Magic.GRAVITY_MAX - Magic.GRAVITY_SPAN : frictDist - Magic.GRAVITY_SPAN, vl2};
        
    }


    /**
     * On-climbable vertical distance checking.
     * @param from
     * @param fromOnGround
     * @param toOnGround
     * @param lastMove 
     * @param thisMove 
     * @param yDistance
     * @param data
     * @return vDistanceAboveLimit
     */
    private double vDistClimbable(final Player player, final PlayerLocation from, final PlayerLocation to,
                                  final boolean fromOnGround, final boolean toOnGround, 
                                  final PlayerMoveData thisMove, final PlayerMoveData lastMove, 
                                  final double yDistance, final MovingData data) {

        // TODO: Might not be able to ignore vertical velocity if moving off climbable (!).
        // TODO: bring in in-medium accounting
        // TODO: ladders are ground !
        double vDistanceAboveLimit = 0.0;
        long now = System.currentTimeMillis();
        data.sfNoLowJump = true;
        data.clearActiveHorVel();
        final double jumpHeight = 1.35 + (data.jumpAmplifier > 0 ? (0.6 + data.jumpAmplifier - 1.0) : 0.0);
        final double maxJumpGain = data.liftOffEnvelope.getMaxJumpGain(data.jumpAmplifier) + 0.1;
        final double maxSpeed = yDistance < 0.0 ? Magic.climbSpeedDescend : Magic.climbSpeedAscend;

        if (Math.abs(yDistance) > maxSpeed) {
            if (from.isOnGround(jumpHeight, 0D, 0D, BlockProperties.F_CLIMBABLE)) {
                if (yDistance > maxJumpGain) {
                    tags.add("climbstep");
                    vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance) - maxSpeed);
                }
            }
            else if (!(lastMove.from.inLiquid 
                    && Math.abs(yDistance) < Magic.swimBaseSpeedV(Bridge1_13.hasIsSwimming()))) {
                tags.add("climbspeed");
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, Math.abs(yDistance) - maxSpeed);
            }
        }

        if (yDistance > 0.0) {
            // Can't climb up with vine not attached to a solid block.
            if (!thisMove.touchedGround && !from.canClimbUp(jumpHeight)) {
                tags.add("climbdetached");
                vDistanceAboveLimit = Math.max(vDistanceAboveLimit, yDistance);
            }
        }

        // Do allow friction with velocity.
        // TODO: Actual friction or limit by absolute y-distance?
        // TODO: Looks like it's only a problem when on ground?
        if (vDistanceAboveLimit > 0.0 && thisMove.yDistance > 0.0 
            && lastMove.yDistance - (Magic.GRAVITY_MAX + Magic.GRAVITY_MIN) / 2.0 > thisMove.yDistance) {
            vDistanceAboveLimit = 0.0;
            tags.add("vfrict_climb");
        }

        // Do allow vertical velocity.
        // TODO: Looks like less velocity is used here (normal hitting 0.361 of 0.462).
        if (vDistanceAboveLimit > 0.0 && data.getOrUseVerticalVelocity(yDistance) != null) {
            vDistanceAboveLimit = 0.0;
        }

        return vDistanceAboveLimit;
    }


    /**
     * In-web vertical distance checking.
     * @param player
     * @param from
     * @param to
     * @param toOnGround
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param now
     * @param data
     * @param cc
     * @return vAllowedDistance, vDistanceAboveLimit
     */
    private double[] vDistWeb(final Player player, final PlayerMoveData thisMove, 
                              final boolean toOnGround, final double hDistanceAboveLimit, final long now, 
                              final MovingData data, final MovingConfig cc, final PlayerLocation from) {
        
        final double yDistance = thisMove.yDistance;
        final boolean step = toOnGround && yDistance > 0.0 && yDistance <= 0.5;
        double vAllowedDistance = 0.0;
        double vDistanceAboveLimit = 0.0;
        data.sfNoLowJump = true;
        data.jumpAmplifier = 0; 

        // Ascend: players cannot ascend in webs
        if (yDistance >= 0.0) {
            vAllowedDistance = step ? yDistance : thisMove.from.onGround ? 0.1 : 0.0; 
            if (step) tags.add("web_step");
            vDistanceAboveLimit = yDistance - vAllowedDistance;
        }
        // Descend
        else {
            if (thisMove.from.resetCond && thisMove.to.resetCond) {
                vAllowedDistance = thisMove.hDistance > 0.018 ? Magic.webSpeedDescendH : Magic.webSpeedDescendDefault;
                vDistanceAboveLimit = yDistance < vAllowedDistance ? Math.abs(yDistance - vAllowedDistance) : 0.0;
            }
        }

        if (vDistanceAboveLimit > 0.0) {
            tags.add(yDistance >= 0.0 ? "vweb" : "vwebdesc");
            
        }

        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }


    /**
     * Berry bush vertical distance checking (1.14+)
     * @param player
     * @param from
     * @param to
     * @param toOnGround
     * @param hDistanceAboveLimit
     * @param yDistance
     * @param now
     * @param data
     * @param cc
     * @return vAllowedDistance, vDistanceAboveLimit
     */
    private double[] vDistBush(final Player player, final PlayerMoveData thisMove, 
                               final boolean toOnGround, final double hDistanceAboveLimit, final long now, 
                               final MovingData data, final MovingConfig cc, final PlayerLocation from,
                               final boolean fromOnGround) {
        
        /* TODO: add something like this through via version, to be able to adapt limits a bit more dinamically for berry bushes
        * if (ServerIsAtLeast1_14 && ClientIsLowerThan1_14){
        *    vAllowedDistance = LiftOffEnvelope.NORMAL.getMaxJumpGain(data.jumpAmplifier) + 0.005
        *    vDistanceAboveLimit = yDistance - vAllowedDistance;
        *(...)
        */

        final double yDistance = thisMove.yDistance;
        final PlayerMoveData lastMove = data.playerMoves.getFirstPastMove();
        // Allow the first move when falling from far above.
        // Observed: Upon first collision with a berry bush, a lot of lost ground cases will apply (pyramid and edgedesc)
        final double descendSpeed = (!fromOnGround && !lastMove.from.inBerryBush && toOnGround && thisMove.to.inBerryBush || thisMove.touchedGroundWorkaround) ? yDistance : Magic.bushSpeedDescend; 
        final boolean multiProtocolPluginPresent = (from.getBlockFlags() & BlockProperties.F_ALLOW_LOWJUMP) != 0; 
        final double defaultLiftOffGain = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier); 
        final double normalMaxGain = LiftOffEnvelope.NORMAL.getMaxJumpGain(data.jumpAmplifier);
        double vAllowedDistance = 0.0;
        double vDistanceAboveLimit = 0.0;
        data.sfNoLowJump = true;

        // Ascend
        // TODO: Friction ?
        if (yDistance >= 0.0) {
            vAllowedDistance = multiProtocolPluginPresent ? normalMaxGain : defaultLiftOffGain; 
            vDistanceAboveLimit = yDistance - vAllowedDistance;
        }
        // Descend
        else {
            vAllowedDistance = multiProtocolPluginPresent ? yDistance : descendSpeed;
            vDistanceAboveLimit = yDistance < vAllowedDistance ? Math.abs(yDistance - vAllowedDistance) : 0.0;
        }

        if (vDistanceAboveLimit > 0.0) {
            tags.add(yDistance >= 0.0 ? "vbush" : "vbushdesc");
        }

        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }


   /**
    * Powder snow vertical distance checking (1.17+): behaves similarly to a climbable block; handled in a separate method
    * because of its properties. (This block is ground with leather boots on)
    * @param yDistance
    * @param form
    * @param to
    * @param cc
    * @param data
    * @return vAllowedDistance, vDistanceAboveLimit
    * 
    */
    private double[] vDistPowderSnow(final double yDistance, final PlayerLocation from, final PlayerLocation to, 
                                     final MovingConfig cc, final MovingData data, final Player player) {
            
        boolean fall = false;
        double vAllowedDistance, vDistanceAboveLimit;
        final double yToBlock = from.getY() - from.getBlockY();

        if (yToBlock <= cc.yOnGround) {
            vAllowedDistance = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier, 1.5);
        }
        else {
            if (Bridge1_17.hasLeatherBootsOn(player)) {
                vAllowedDistance = yDistance < 0 ? -Magic.snowClimbSpeedDescend : Magic.snowClimbSpeedAscend;
            } 
            else {
                vAllowedDistance = -Magic.snowClimbSpeedDescend;
                fall = true;
            }
        }
        final double yDistDiffEx = yDistance - vAllowedDistance;
        final boolean violation = fall ? Math.abs(yDistDiffEx) > 0.05 : Math.abs(yDistance) > Math.abs(vAllowedDistance);
        vDistanceAboveLimit = violation ? yDistance : 0;
        if (vDistanceAboveLimit > 0.0) {
            tags.add(yDistance >= 0.0 ? "snowasc" : "snowdesc");
        }
        return new double[]{vAllowedDistance, vDistanceAboveLimit};
    }


    /**
     * Violation handling put here to have less code for the frequent processing of check.
     * @param now
     * @param result
     * @param player
     * @param from
     * @param to
     * @param data
     * @param cc
     * @return
     */
    private Location handleViolation(final long now, final double result, 
                                    final Player player, final PlayerLocation from, final PlayerLocation to, 
                                    final MovingData data, final MovingConfig cc){

        // Increment violation level.
        if (Double.isInfinite(data.survivalFlyVL)) data.survivalFlyVL = 0;
        data.survivalFlyVL += result;
        //data.sfVLTime = data.getPlayerMoveCount();
        final ViolationData vd = new ViolationData(this, player, data.survivalFlyVL, result, cc.survivalFlyActions);
        if (vd.needsParameters()) {
            vd.setParameter(ParameterName.LOCATION_FROM, String.format(Locale.US, "%.2f, %.2f, %.2f", from.getX(), from.getY(), from.getZ()));
            vd.setParameter(ParameterName.LOCATION_TO, String.format(Locale.US, "%.2f, %.2f, %.2f", to.getX(), to.getY(), to.getZ()));
            vd.setParameter(ParameterName.DISTANCE, String.format(Locale.US, "%.2f", TrigUtil.distance(from, to)));
            vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
        }
        // Some resetting is done in MovingListener.
        if (executeActions(vd).willCancel()) {
            data.sfVLTime = data.getPlayerMoveCount();
            // Set back + view direction of to (more smooth).
            return MovingUtil.getApplicableSetBackLocation(player, to.getYaw(), to.getPitch(), to, data, cc);
        }
        else {
            data.sfVLTime = data.getPlayerMoveCount();
            // TODO: Evaluate how data resetting can be done minimal (skip certain things flags)?
            data.clearAccounting();
            data.sfJumpPhase = 0;
            // Cancelled by other plugin, or no cancel set by configuration.
            return null;
        }
    }

    
    /**
     * Hover violations have to be handled in this check, because they are handled as SurvivalFly violations (needs executeActions).
     * @param player
     * @param loc
     * @param blockCache 
     * @param cc
     * @param data
     */
    public final void handleHoverViolation(final Player player, final PlayerLocation loc, final MovingConfig cc, final MovingData data) {
        if (Double.isInfinite(data.survivalFlyVL)) data.survivalFlyVL = 0;
        data.survivalFlyVL += cc.sfHoverViolation;

        // TODO: Extra options for set back / kick, like vl?
        data.sfVLTime = data.getPlayerMoveCount();
        data.sfVLInAir = true;
        final ViolationData vd = new ViolationData(this, player, data.survivalFlyVL, cc.sfHoverViolation, cc.survivalFlyActions);
        if (vd.needsParameters()) {
            vd.setParameter(ParameterName.LOCATION_FROM, String.format(Locale.US, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ()));
            vd.setParameter(ParameterName.LOCATION_TO, "(HOVER)");
            vd.setParameter(ParameterName.DISTANCE, "0.0(HOVER)");
            vd.setParameter(ParameterName.TAGS, "hover");
        }
        if (executeActions(vd).willCancel()) {
            // Set back or kick.
            final Location newTo = MovingUtil.getApplicableSetBackLocation(player, loc.getYaw(), loc.getPitch(), loc, data, cc);
            if (newTo != null) {
                data.prepareSetBack(newTo);
                player.teleport(newTo, BridgeMisc.TELEPORT_CAUSE_CORRECTION_OF_POSITION);
            }
            else {
                // Solve by extra actions ? Special case (probably never happens)?
                player.kickPlayer("Hovering?");
            }
        }
        else {
            // Ignore.
        }
    }


    /**
     * This is set with PlayerToggleSneak, to be able to distinguish players that are really sneaking from players that are set sneaking by a plugin. 
     * @param player + ")"
     * @param sneaking
     */
    public void setReallySneaking(final Player player, final boolean sneaking) {
        if (sneaking) reallySneaking.add(player.getName());
        else reallySneaking.remove(player.getName());
    }


   /** 
    * Collect the F_STICKY block flag. Clear NoFall's data upon side collision.
    * @param from
    * @param to
    * @param data
    */
    private boolean isCollideWithHB(PlayerLocation from, PlayerLocation to, MovingData data) {

        final boolean isFlagCollected = (to.getBlockFlags() & BlockProperties.F_STICKY) != 0;
        // Moving on side block, remove nofall data
        if (isFlagCollected && !to.isOnGround() && BlockProperties.collides(to.getBlockCache(),
                                                        to.getMinX() - 0.01, to.getMinY(), to.getMinZ() - 0.01, 
                                                        to.getMaxX() + 0.01, to.getMaxY(), to.getMaxZ() + 0.01, 
                                                        BlockProperties.F_STICKY)
            ) {
            data.clearNoFallData();
        }
        return isFlagCollected;
    }

    // Model now correct but still flags
    // Reproduce : hanging lantern with 2 block space below, spam jump
    // TODO: Soul lantern too; research this magic thing
    // Left notes on Discord: NCP doesn't see the player touching the ground again after headObstr.
    // Rather than an actual lostground case this might also be tied to the onGround logic, since NCP also checks the block above against spider
    // cheats and the like. Probably something is causing the check to return false when landing back after headObstr (?)
    private boolean isLanternUpper(PlayerLocation from) {
        World w = from.getWorld();
        final int x = from.getBlockX();
        final int y = from.getBlockY() + 2;
        final int z = from.getBlockZ();
        if (w.getBlockAt(x, y, z).getType().toString().equals("LANTERN")) return true;
        return false;
    }



    /**
     * Debug output.
     * @param player
     * @param to
     * @param data
     * @param cc
     * @param hDistance
     * @param hAllowedDistance
     * @param hFreedom
     * @param yDistance
     * @param vAllowedDistance
     * @param fromOnGround
     * @param resetFrom
     * @param toOnGround
     * @param resetTo
     */
    private void outputDebug(final Player player, final PlayerLocation to, 
                             final MovingData data, final MovingConfig cc, 
                             final double hDistance, final double hAllowedDistance, final double hFreedom, 
                             final double yDistance, final double vAllowedDistance,
                             final boolean fromOnGround, final boolean resetFrom, 
                             final boolean toOnGround, final boolean resetTo,
                             final PlayerMoveData thisMove, double vDistanceAboveLimit) {

        // TODO: Show player name once (!)
        final PlayerMoveData lastMove = data.playerMoves.getFirstPastMove();
        final double yDistDiffEx = yDistance - vAllowedDistance;
        final StringBuilder builder = new StringBuilder(500);
        builder.append(CheckUtils.getLogMessagePrefix(player, type));
        final String hBuf = (data.sfHorizontalBuffer < 1.0 ? ((" , buffer: " + StringUtil.fdec3.format(data.sfHorizontalBuffer))) : "");
        final String lostSprint = (data.lostSprintCount > 0 ? (" , lostSprint: " + data.lostSprintCount) : "");
        final String hVelUsed = hFreedom > 0 ? " , hVelUsed: " + StringUtil.fdec3.format(hFreedom) : "";
        builder.append("\nOnGround: " + (thisMove.headObstructed ? "(head obstr.) " : "") + (thisMove.touchedGroundWorkaround ? "(touched ground) " : "") + (fromOnGround ? "onground -> " : (resetFrom ? "resetcond -> " : "--- -> ")) + (toOnGround ? "onground" : (resetTo ? "resetcond" : "---")) + ", jumpphase: " + data.sfJumpPhase + ", liftoff: " + data.liftOffEnvelope.name() + "(" + data.insideMediumCount + ")");
        final String dHDist = lastMove.toIsValid ? "(" + StringUtil.formatDiff(hDistance, lastMove.hDistance) + ")" : "";
        final String dYDist = lastMove.toIsValid ? "(" + StringUtil.formatDiff(yDistance, lastMove.yDistance)+ ")" : "";
        final String hopDelay = (data.bunnyhopDelay > 0 ? ("bHopDelay: " + data.bunnyhopDelay) + " , " : "");
        final String hopTick = (data.bunnyhopTick > 0 ? ("bHopTick: " + data.bunnyhopTick) + " , " : "");
        final String onIceTick = (data.iceFrictionTick > 0 ? ("iceFrictionTick: " + data.iceFrictionTick) + " , " : "");
        final String frictionTick = ("keepFrictionTick= " + data.keepfrictiontick + " , ");
        builder.append("\n Tick counters: " + hopDelay + hopTick + onIceTick + frictionTick);
        builder.append("\n" + " hDist: " + StringUtil.fdec3.format(hDistance) + dHDist + " / Allowed: " + StringUtil.fdec3.format(hAllowedDistance) + hBuf + lostSprint + hVelUsed +
                       "\n" + " vDist: " + StringUtil.fdec3.format(yDistance) + dYDist + " / Expected diff: " + StringUtil.fdec3.format(yDistDiffEx) + " / Allowed: " + StringUtil.fdec3.format(vAllowedDistance) + " , setBackY: " + (data.hasSetBack() ? (data.getSetBackY() + " (setBackYDistance: " + StringUtil.fdec3.format(to.getY() - data.getSetBackY()) + " / MaxJumpHeight: " + data.liftOffEnvelope.getMaxJumpHeight(data.jumpAmplifier) + ")") : "?"));
        if (lastMove.toIsValid) {
            builder.append(" , fdsq: " + StringUtil.fdec3.format(thisMove.distanceSquared / lastMove.distanceSquared));
        }
        if (thisMove.verVelUsed != null) {
            builder.append(" , vVelUsed: " + thisMove.verVelUsed + " ");
        }
        data.addVerticalVelocity(builder);
        //      if (data.horizontalVelocityCounter > 0 || data.horizontalFreedom >= 0.001) {
        //          builder.append("\n" + player.getName() + " horizontal freedom: " +  StringUtil.fdec3.format(data.horizontalFreedom) + " (counter=" + data.horizontalVelocityCounter +"/used="+data.horizontalVelocityUsed);
        // }
        data.addHorizontalVelocity(builder);
        if (!resetFrom && !resetTo) {
            if (Magic.changedHDir(thisMove, lastMove)){
                builder.append("\n" + " Changed horizontal direction (strafe)");
            }
            if (cc.survivalFlyAccountingV && data.vDistAcc.count() > data.vDistAcc.bucketCapacity()) {
                builder.append("\n" + " vAcc: " + data.vDistAcc.toInformalString());
            }
        }
        if (cc.survivalFlyAccountingH && data.combinedMediumHCount > 0) {
            builder.append("\n hAcc: fcmh" + StringUtil.fdec3.format(data.combinedMediumHValue / (double) data.combinedMediumHCount) + "(" + data.combinedMediumHCount + ")");
        }
        if (player.isSleeping()) {
            tags.add("sleeping");
        }
        if (player.getFoodLevel() <= 5 && player.isSprinting()) {
            // Exception: does not take into account latency.
            tags.add("lowfoodsprint");
        }
        if (Bridge1_9.isWearingElytra(player)) {
            // Just wearing (not isGliding).
            tags.add("elytra_off");
        }
        if (!tags.isEmpty()) {
            builder.append("\n" + " Tags: " + StringUtil.join(tags, "+"));
        }
        if (!justUsedWorkarounds.isEmpty()) {
            builder.append("\n" + " Rules/Workarounds: " + StringUtil.join(justUsedWorkarounds, " , "));
        }
        builder.append("\n");
        //      builder.append(data.stats.getStatsStr(false));
        NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, builder.toString());
    }

    
    private void logPostViolationTags(final Player player) {
        debug(player, "SurvivalFly Post violation handling tag update:\n" + StringUtil.join(tags, "+"));
    }
}