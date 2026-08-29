package com.charybdis180.ethological.util;

import com.charybdis180.ethological.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;

/**
 * Gives animals traversing water a gentle speed boost so they can carry enough
 * horizontal momentum to climb out onto a bank.
 *
 * <p>Vanilla {@code Mob.travel()} moves a flop non-swimmer at roughly {@code getSpeed()}
 * damped by ~0.72/tick — a cow falling into a pond barely drifts, and when it reaches
 * the shore it has no horizontal velocity to hop the ledge. Rather than force swimmers
 * into the swimming pose (a cow doing the crawl looks wrong and changes render posture),
 * or globally speed up idle wading, this boosts {@code MOVEMENT_SPEED} transiently but
 * only while the animal is in water AND actively navigating toward something (any live
 * path: seek-shore, follow, migrate, drink, return-home). The moment it is beached or
 * pathless, the modifier is removed so nothing else changes.
 *
 * <p>The modifier is added/removed on state transitions, never re-evaluated per tick, so
 * a herd crossing a river pays a couple of attribute writes total.
 */
public final class SwimAssist {
    private static final ResourceLocation SWIM_BOOST_ID = ResourceLocation.withDefaultNamespace("ethological_swim_boost");
    /** Multiply the base movement speed while pathing in water. ~1.6x feels like
     *  "swimming a bit faster" without turning every puddle crossing into a cannonball. */
    private static final double SWIM_BOOST_MULT = 1.6;

    private SwimAssist() {
    }

    /** Call once per server tick per Animal. Cheap: touches the attribute only on entry/exit. */
    public static void update(Animal animal) {
        if (animal.isDeadOrDying() || animal.isRemoved()) {
            return;
        }
        boolean inWaterWithPath = animal.isInWaterOrBubble() && SwimAssist.activePath(animal)
                && !animal.getData(ModAttachments.SLEEPING) && !animal.getData(ModAttachments.RESTING);
        AttributeInstance speed = animal.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        boolean hasBoost = speed.getModifier(SwimAssist.SWIM_BOOST_ID) != null;
        if (inWaterWithPath && !hasBoost) {
            double base = speed.getBaseValue();
            double amount = base * (SwimAssist.SWIM_BOOST_MULT - 1.0);
            speed.addTransientModifier(new AttributeModifier(
                    SwimAssist.SWIM_BOOST_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!inWaterWithPath && hasBoost) {
            speed.removeModifier(SwimAssist.SWIM_BOOST_ID);
        }
    }

    /** True when the mob has a live path toward a non-null target. */
    private static boolean activePath(Animal animal) {
        net.minecraft.world.entity.ai.navigation.PathNavigation nav = animal.getNavigation();
        if (nav == null) {
            return false;
        }
        net.minecraft.world.level.pathfinder.Path path = nav.getPath();
        return path != null && !path.isDone() && path.getTarget() != null;
    }
}