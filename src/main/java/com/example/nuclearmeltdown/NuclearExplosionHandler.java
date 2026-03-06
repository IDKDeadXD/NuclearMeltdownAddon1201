package com.example.nuclearmeltdown;

import mekanism.common.lib.radiation.Meltdown;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Nuclear bomb explosion sequence for Mekanism fission reactor meltdowns (Forge 1.20.1 port).
 *
 * Sequence:
 *   t=0   Ground-zero cluster: 6 overlapping blasts obliterate the reactor site.
 *         Crater carved (radius 25 sphere of air, lava at the bottom).
 *         Terrain scorched to netherrack within 100 blocks.
 *         Entity damage/knockback within 300 blocks.
 *         Fire set within 200 blocks.
 *   t=0→100  Continuous shockwave: 50 rings expand from radius 20 to 200,
 *             one ring every 2 ticks, 20 blasts per ring.
 */
public class NuclearExplosionHandler {

    private static final Set<UUID> triggeredMeltdowns = new HashSet<>();
    private static final List<ScheduledRing> pendingRings = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private static final float CENTRAL_RADIUS = 35.0f;
    private static final int CLUSTER_COUNT = 5;
    private static final float CLUSTER_RADIUS = 25.0f;
    private static final float CLUSTER_SPREAD = 10.0f;

    private static final int SHOCKWAVE_DURATION_TICKS = 100;
    private static final int SHOCKWAVE_RING_INTERVAL = 2;
    private static final float SHOCKWAVE_START_RADIUS = 20.0f;
    private static final float SHOCKWAVE_END_RADIUS = 200.0f;

    private static final int RING_POINTS = 20;
    private static final float RING_EXPLOSION_RADIUS = 10.0f;

    private static final int CRATER_RADIUS = 25;
    private static final float LAVA_FILL_FRACTION = 0.35f;

    private static final int SCORCH_RADIUS = 100;
    private static final double ENTITY_DAMAGE_RADIUS = 300.0;
    private static final int FIRE_RADIUS = 200;

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Hold reference as the vanilla Explosion type so getPosition() resolves against
        // the properly-deobfuscated vanilla class rather than the obfuscated Mekanism subtype.
        net.minecraft.world.level.Explosion explosion = event.getExplosion();
        if (!(explosion instanceof Meltdown.MeltdownExplosion meltdown)) {
            return;
        }
        if (!triggeredMeltdowns.add(meltdown.getMultiblockID())) {
            return;
        }
        Vec3 pos = explosion.getPosition();
        initiateNuclearBlast(serverLevel, pos.x, pos.y, pos.z);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (pendingRings.isEmpty()) {
            return;
        }
        List<ScheduledRing> remaining = new ArrayList<>();
        for (ScheduledRing ring : pendingRings) {
            if (ring.ticksRemaining <= 0) {
                fireShockwaveRing(ring.level, ring.cx, ring.cy, ring.cz, ring.ringRadius);
            } else {
                remaining.add(ring.tick());
            }
        }
        pendingRings.clear();
        pendingRings.addAll(remaining);
    }

    // -------------------------------------------------------------------------
    // Nuclear blast sequence
    // -------------------------------------------------------------------------

    private static void initiateNuclearBlast(ServerLevel level, double cx, double cy, double cz) {
        // Ground zero: central detonation + surrounding cluster
        level.explode(null, cx, cy, cz, CENTRAL_RADIUS, true, Level.ExplosionInteraction.BLOCK);
        for (int i = 0; i < CLUSTER_COUNT; i++) {
            double ox = (level.getRandom().nextDouble() * 2 - 1) * CLUSTER_SPREAD;
            double oy = (level.getRandom().nextDouble() * 2 - 1) * (CLUSTER_SPREAD * 0.5);
            double oz = (level.getRandom().nextDouble() * 2 - 1) * CLUSTER_SPREAD;
            level.explode(null, cx + ox, cy + oy, cz + oz, CLUSTER_RADIUS, true, Level.ExplosionInteraction.BLOCK);
        }

        carveCrater(level, cx, cy, cz);
        scorchTerrain(level, cx, cy, cz);

        // Schedule continuous expanding shockwave
        int numRings = SHOCKWAVE_DURATION_TICKS / SHOCKWAVE_RING_INTERVAL;
        for (int i = 0; i <= numRings; i++) {
            int delay = i * SHOCKWAVE_RING_INTERVAL;
            float t = (numRings == 0) ? 1.0f : (float) i / numRings;
            float ringRadius = SHOCKWAVE_START_RADIUS + t * (SHOCKWAVE_END_RADIUS - SHOCKWAVE_START_RADIUS);
            pendingRings.add(new ScheduledRing(level, cx, cy, cz, ringRadius, delay));
        }

        damageEntitiesInRadius(level, cx, cy, cz);
        setFireInRadius(level, cx, cy, cz, FIRE_RADIUS);
    }

    // -------------------------------------------------------------------------
    // Shockwave ring
    // -------------------------------------------------------------------------

    private static void fireShockwaveRing(ServerLevel level, double cx, double cy, double cz, float ringRadius) {
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = 2 * Math.PI * i / RING_POINTS;
            double x = cx + ringRadius * Math.cos(angle);
            double z = cz + ringRadius * Math.sin(angle);
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
            level.explode(null, x, surfaceY, z, RING_EXPLOSION_RADIUS, true, Level.ExplosionInteraction.BLOCK);
        }
    }

    // -------------------------------------------------------------------------
    // Crater
    // -------------------------------------------------------------------------

    private static void carveCrater(ServerLevel level, double cx, double cy, double cz) {
        int r = CRATER_RADIUS;
        int lavaThreshold = (int) (r - r * LAVA_FILL_FRACTION * 2);
        BlockPos center = BlockPos.containing(cx, cy, cz);

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) continue;
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState here = level.getBlockState(pos);
                    if (here.is(Blocks.BEDROCK) || here.isAir()) continue;
                    if (dy < -lavaThreshold) {
                        level.setBlock(pos, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
                    } else {
                        level.removeBlock(pos, false);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scorched terrain
    // -------------------------------------------------------------------------

    private static void scorchTerrain(ServerLevel level, double cx, double cy, double cz) {
        int r = SCORCH_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = (int) cx + dx;
                int z = (int) cz + dz;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int dy = 0; dy >= -3; dy--) {
                    BlockPos pos = new BlockPos(x, surfaceY + dy, z);
                    net.minecraft.world.level.block.Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT
                            || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT
                            || block == Blocks.SAND || block == Blocks.RED_SAND
                            || block == Blocks.GRAVEL || block == Blocks.MYCELIUM
                            || block == Blocks.PODZOL) {
                        level.setBlock(pos, Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Entity damage
    // -------------------------------------------------------------------------

    private static void damageEntitiesInRadius(ServerLevel level, double cx, double cy, double cz) {
        double r = ENTITY_DAMAGE_RADIUS;
        AABB searchBox = new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
        List<Entity> entities = level.getEntities((Entity) null, searchBox, e -> true);

        for (Entity entity : entities) {
            double dist = entity.position().distanceTo(new Vec3(cx, cy, cz));
            if (dist > r) continue;

            double knockbackStrength = Math.max(2.0, 50.0 * Math.pow(1.0 - dist / r, 1.5));
            Vec3 away = entity.position().subtract(cx, cy, cz);
            if (away.lengthSqr() > 0) {
                entity.addDeltaMovement(away.normalize().scale(knockbackStrength / 20.0));
            }

            if (!(entity instanceof LivingEntity living)) continue;

            float damage;
            if (dist < 50) {
                damage = 1_000.0f;
            } else if (dist < 150) {
                damage = (float) (600.0 * (1.0 - (dist - 50.0) / 100.0));
            } else {
                damage = (float) (120.0 * (1.0 - (dist - 150.0) / 150.0));
            }
            living.hurt(level.damageSources().explosion(null, null), damage);
        }
    }

    // -------------------------------------------------------------------------
    // Fire spread
    // -------------------------------------------------------------------------

    private static void setFireInRadius(ServerLevel level, double cx, double cy, double cz, int radius) {
        int attempts = (int) (Math.PI * radius * radius / 1.5);
        for (int i = 0; i < attempts; i++) {
            double angle = level.getRandom().nextDouble() * 2 * Math.PI;
            double r = level.getRandom().nextDouble() * radius;
            int x = (int) (cx + r * Math.cos(angle));
            int z = (int) (cz + r * Math.sin(angle));
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (level.isEmptyBlock(pos)) {
                level.setBlock(pos, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal data class
    // -------------------------------------------------------------------------

    private static final class ScheduledRing {
        final ServerLevel level;
        final double cx, cy, cz;
        final float ringRadius;
        final int ticksRemaining;

        ScheduledRing(ServerLevel level, double cx, double cy, double cz, float ringRadius, int ticksRemaining) {
            this.level = level;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.ringRadius = ringRadius;
            this.ticksRemaining = ticksRemaining;
        }

        ScheduledRing tick() {
            return new ScheduledRing(level, cx, cy, cz, ringRadius, ticksRemaining - 1);
        }
    }
}
