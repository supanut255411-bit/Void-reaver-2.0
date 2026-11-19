package dev.voidreaver.ultimate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import dev.voidreaver.chain.ChainManager;
import dev.voidreaver.config.BlockMatcher;
import dev.voidreaver.config.VoidreaverConfig;
import dev.voidreaver.net.ShockwavePayload;
import dev.voidreaver.net.UltPhasePayload;
import dev.voidreaver.util.CooldownSync;
import dev.voidreaver.util.Net;
import dev.voidreaver.util.Sfx;

/**
 * APOCALYPSE STOMP. Server-authoritative state machine per player:
 * CHARGE, ASCENT, SUSPENSION, DESCENT, impact, RECOVERY, then the (independent) cooldown.
 * The shockwave is a separate object that keeps running even if the player leaves, so there are no dangling states.
 *
 * Terrain: the wave only ever touches the exposed surface (the top block of a column, optionally one or two below it), at a
 * configurable density, turning those blocks into real falling-block entities. It never digs, so no crater. The work is queued
 * and spread over many ticks; the radius itself is never reduced.
 */
public final class UltimateManager {
	private enum Phase { CHARGE, ASCENT, SUSPENSION, DESCENT, RECOVERY }

	private static final class State {
		final UUID owner;
		final ServerLevel level;
		Phase phase = Phase.CHARGE;
		int ticks;
		double startY;
		double lastY;
		int stall;
		double speed;

		State(UUID owner, ServerLevel level, double startY) {
			this.owner = owner;
			this.level = level;
			this.startY = startY;
			this.lastY = startY;
		}
	}

	private record Victim(LivingEntity entity, double distance) { }

	private static final class Debris {
		final FallingBlockEntity entity;
		final int expireTick;

		Debris(FallingBlockEntity entity, int expireTick) {
			this.entity = entity;
			this.expireTick = expireTick;
		}
	}

	private static final class Wave {
		final ServerLevel level;
		final UUID owner;
		final Vec3 center;
		final double radius;
		final double speed;
		double current;
		final List<Victim> victims = new ArrayList<>();
		int victimIndex;
		final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		int spawned;
		int frontsPlayed;

		Wave(ServerLevel level, UUID owner, Vec3 center, double radius, double speed) {
			this.level = level;
			this.owner = owner;
			this.center = center;
			this.radius = radius;
			this.speed = speed;
		}
	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	private static final Map<UUID, Integer> COOLDOWN_END = new HashMap<>();
	private static final Map<UUID, Integer> IMMUNE_UNTIL = new HashMap<>();
	private static final List<Wave> WAVES = new ArrayList<>();
	private static final List<Debris> DEBRIS = new ArrayList<>();
	private static final BlockMatcher PROTECTED = new BlockMatcher(() -> VoidreaverConfig.get().ultimateProtectedBlocks);
	private static final int MAX_QUEUE = 6000;
	private static final int MAX_SAMPLES_PER_TICK = 900;

	private UltimateManager() { }

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(UltimateManager::tick);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			ACTIVE.remove(id);
			IMMUNE_UNTIL.remove(id);
		});

		// Protect the user only from what the ability itself can do to them, and only while it runs (plus a short grace
		// period). Everything else, including other players' attacks, still hurts, so this is never permanent invulnerability.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player && isImmune(player) && isSelfInflicted(player, source)) {
				return false;
			}

			return true;
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			COOLDOWN_END.clear();
			IMMUNE_UNTIL.clear();
			WAVES.clear();
			DEBRIS.clear();
		});
	}

	// ------------------------------------------------------------------ queries

	public static boolean isActive(UUID id) {
		return ACTIVE.containsKey(id);
	}

	public static int cooldownLeft(UUID id, int now) {
		Integer end = COOLDOWN_END.get(id);
		return end == null ? 0 : Math.max(0, end - now);
	}

	private static boolean isImmune(ServerPlayer player) {
		if (ACTIVE.containsKey(player.getUUID())) {
			return true;
		}

		Integer until = IMMUNE_UNTIL.get(player.getUUID());
		return until != null && player.level().getServer().getTickCount() < until;
	}

	private static boolean isSelfInflicted(ServerPlayer player, DamageSource source) {
		Holder<DamageType> type = source.typeHolder();
		return type.is(DamageTypes.FALL)
				|| type.is(DamageTypes.FALLING_BLOCK)
				|| type.is(DamageTypes.IN_WALL)
				|| type.is(DamageTypes.FLY_INTO_WALL)
				|| source.getEntity() == player;
	}

	// ------------------------------------------------------------------ activation

	public static boolean tryActivate(ServerPlayer player) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		ServerLevel level = (ServerLevel) player.level();
		int now = level.getServer().getTickCount();
		UUID id = player.getUUID();

		if (ACTIVE.containsKey(id) || ChainManager.isActive(id) || player.isSpectator()) {
			return false;
		}

		int left = cooldownLeft(id, now);

		if (left > 0) {
			player.displayClientMessage(Component.literal(String.format("Apocalypse Stomp recharging: %.1fs", left / 20.0)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
			return false;
		}

		ACTIVE.put(id, new State(id, level, player.getY()));
		COOLDOWN_END.put(id, now + cfg.ultimateCooldownTicks); // cooldown starts on successful activation, independent of the chain
		CooldownSync.send(player);
		sendPhase(player, UltPhasePayload.CHARGE);

		Vec3 pos = player.position();
		Sfx.sound(level, pos, "block.beacon.activate", 2.5f, 0.6f);
		Sfx.sound(level, pos, "block.respawn_anchor.charge", 1.5f, 0.6f);
		Sfx.soundIn(level, pos, 2, "entity.warden.sonic_charge", 2.5f, 0.9f);
		Sfx.soundIn(level, pos, 4, "item.armor.equip_chain", 1.2f, 0.7f);
		return true;
	}

	// ------------------------------------------------------------------ tick

	private static void tick(MinecraftServer server) {
		int now = server.getTickCount();

		if (now % 1200 == 0) {
			COOLDOWN_END.values().removeIf(end -> end <= now);
			IMMUNE_UNTIL.values().removeIf(end -> end <= now);
		}

		if (!ACTIVE.isEmpty()) {
			Iterator<State> iterator = ACTIVE.values().iterator();

			while (iterator.hasNext()) {
				State state = iterator.next();
				ServerPlayer player = server.getPlayerList().getPlayer(state.owner);

				if (player == null) {
					iterator.remove();
					continue;
				}

				boolean valid = player.isAlive() && !player.isRemoved() && !player.isSpectator() && player.level() == state.level;

				if (!valid) {
					sendPhase(player, UltPhasePayload.END);
					iterator.remove();
					continue;
				}

				if (step(player, state, now)) {
					IMMUNE_UNTIL.put(state.owner, now + VoidreaverConfig.get().ultimateImmunityGraceTicks);
					sendPhase(player, UltPhasePayload.END);
					iterator.remove();
				}
			}
		}

		tickWaves(server, now);
		tickDebris(now);
	}

	/** Advances one player's state machine. Returns true when the whole sequence is over. */
	private static boolean step(ServerPlayer player, State state, int now) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		state.ticks++;
		player.resetFallDistance();

		switch (state.phase) {
			case CHARGE -> {
				if (state.ticks >= cfg.ultimateChargeTicks) {
					enter(player, state, Phase.ASCENT, UltPhasePayload.ASCENT);
					state.speed = 0.9;
					Sfx.sound(state.level, player.position(), "entity.firework_rocket.launch", 3.0f, 0.6f);
					Sfx.soundIn(state.level, player.position(), 1, "item.trident.riptide_3", 3.0f, 0.7f);
					Sfx.soundIn(state.level, player.position(), 2, "entity.wind_charge.wind_burst", 2.0f, 0.7f);
				}
			}
			case ASCENT -> {
				state.speed = Math.min(cfg.ultimateAscentSpeed, state.speed + 0.5);
				player.setDeltaMovement(0.0, state.speed, 0.0);
				player.hurtMarked = true;

				boolean high = player.getY() >= state.startY + cfg.ultimateAscentHeight;

				// Ceiling: no upward progress for a few ticks (after the launch has had time to start).
				if (state.ticks > 6 && player.getY() - state.lastY < 0.05) {
					state.stall++;
				} else {
					state.stall = 0;
				}

				state.lastY = player.getY();

				if (high || state.stall >= 4) {
					player.setDeltaMovement(Vec3.ZERO);
					player.hurtMarked = true;
					enter(player, state, Phase.SUSPENSION, UltPhasePayload.SUSPENSION);
					Sfx.sound(state.level, player.position(), "block.beacon.power_select", 1.2f, 0.7f); // one quiet cue: the pause before the dive
				}
			}
			case SUSPENSION -> {
				player.setDeltaMovement(Vec3.ZERO);
				player.hurtMarked = true;

				if (state.ticks >= cfg.ultimateSuspensionTicks) {
					enter(player, state, Phase.DESCENT, UltPhasePayload.DESCENT);
					state.speed = 1.5;
					state.lastY = player.getY();
					Sfx.sound(state.level, player.position(), "item.trident.riptide_3", 3.0f, 0.5f);
				}
			}
			case DESCENT -> {
				state.speed = Math.min(cfg.ultimateDiveSpeed, state.speed + 0.9);
				player.setDeltaMovement(0.0, -state.speed, 0.0);
				player.hurtMarked = true;

				boolean stalled = state.ticks > 3 && state.lastY - player.getY() < 0.05;
				state.lastY = player.getY();

				if (state.ticks % 4 == 0) {
					Sfx.sound(state.level, player.position(), "item.trident.riptide_2", 2.0f, 0.5f + (float) (state.speed / cfg.ultimateDiveSpeed) * 0.6f);
				}

				if (state.ticks > 3 && (player.onGround() || player.isInWater() || player.isInLava() || stalled) || state.ticks > 200) {
					impact(player, state, now);
					enter(player, state, Phase.RECOVERY, UltPhasePayload.RECOVERY);
				}
			}
			case RECOVERY -> {
				if (state.ticks >= cfg.ultimateRecoveryTicks) {
					return true;
				}
			}
		}

		return false;
	}

	private static void enter(ServerPlayer player, State state, Phase phase, int code) {
		state.phase = phase;
		state.ticks = 0;
		sendPhase(player, code);
	}

	private static void sendPhase(ServerPlayer player, int phase) {
		Net.toOwnerAndWatchers(player, new UltPhasePayload(player.getId(), phase));
	}

	// ------------------------------------------------------------------ impact and wave

	private static void impact(ServerPlayer player, State state, int now) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		ServerLevel level = state.level;
		Vec3 center = player.position();

		player.setDeltaMovement(Vec3.ZERO);
		player.hurtMarked = true;
		player.resetFallDistance();

		Wave wave = new Wave(level, player.getUUID(), center, cfg.ultimateRadius, cfg.ultimateWaveSpeed);

		// One snapshot of everything in range, sorted by distance, instead of searching the area again every tick.
		AABB area = new AABB(center, center).inflate(cfg.ultimateRadius, cfg.ultimateEntityVerticalReach, cfg.ultimateRadius);

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator())) {
			double dx = entity.getX() - center.x;
			double dz = entity.getZ() - center.z;
			double distance = Math.sqrt(dx * dx + dz * dz);

			if (distance <= cfg.ultimateRadius) {
				wave.victims.add(new Victim(entity, distance));
			}
		}

		wave.victims.sort(Comparator.comparingDouble(Victim::distance));
		WAVES.add(wave);

		Net.around(level, center, cfg.ultimateRadius + 64.0,
				new ShockwavePayload(ShockwavePayload.ULTIMATE, center.x, center.y, center.z, (float) cfg.ultimateRadius, (float) cfg.ultimateWaveSpeed, 2.4f));

		// Volumes above 1 carry further, so the impact can be heard across the whole 100 blocks.
		// One enormous impact built from layers a tick or two apart: heavy mace hit, explosion, metal, thunder, energy, aftershock.
		Sfx.sound(level, center, "item.mace.smash_ground_heavy", 8.0f, 0.55f);
		Sfx.sound(level, center, "entity.generic.explode", 8.0f, 0.45f);
		Sfx.soundIn(level, center, 1, "block.anvil.land", 6.0f, 0.5f);
		Sfx.soundIn(level, center, 2, "entity.lightning_bolt.thunder", 10.0f, 0.65f);
		Sfx.soundIn(level, center, 2, "block.respawn_anchor.deplete", 4.0f, 0.7f);
		Sfx.soundIn(level, center, 3, "entity.warden.sonic_boom", 6.0f, 0.8f);
		Sfx.soundIn(level, center, 5, "block.netherite_block.hit", 5.0f, 0.6f);
	}

	private static void tickWaves(MinecraftServer server, int now) {
		if (WAVES.isEmpty()) {
			return;
		}

		VoidreaverConfig cfg = VoidreaverConfig.get();
		Iterator<Wave> iterator = WAVES.iterator();

		while (iterator.hasNext()) {
			Wave wave = iterator.next();
			double previous = wave.current;
			wave.current = Math.min(wave.radius, wave.current + wave.speed);
			ServerPlayer owner = server.getPlayerList().getPlayer(wave.owner);

			// Sound travels with the front: a few rumbles at fixed fractions of the radius, not one per block.
			while (wave.frontsPlayed < 4 && wave.current >= wave.radius * (wave.frontsPlayed + 1) * 0.25) {
				playFront(wave, wave.frontsPlayed);
				wave.frontsPlayed++;
			}

			// Entities: hit everything the front has passed since last tick.
			while (wave.victimIndex < wave.victims.size() && wave.victims.get(wave.victimIndex).distance() <= wave.current) {
				hitVictim(wave, wave.victims.get(wave.victimIndex), owner);
				wave.victimIndex++;
			}

			// Blocks: sample the band the front just crossed, then spawn debris from the queue in small batches.
			if (owner != null) {
				sampleBand(wave, previous, wave.current, owner);
				drain(wave, owner, now);
			} else {
				wave.queue.clear(); // owner left: stop touching terrain
			}

			if (wave.current >= wave.radius && wave.victimIndex >= wave.victims.size() && wave.queue.isEmpty()) {
				iterator.remove();
			}
		}
	}

	private static void playFront(Wave wave, int index) {
		ThreadLocalRandom random = ThreadLocalRandom.current();

		for (int i = 0; i < 2; i++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			Vec3 pos = new Vec3(wave.center.x + Math.cos(angle) * wave.current, wave.center.y, wave.center.z + Math.sin(angle) * wave.current);
			Sfx.sound(wave.level, pos, "entity.generic.explode", 5.0f, 0.6f - 0.04f * index);
		}

		Sfx.sound(wave.level, wave.center.add(Math.cos(index) * wave.current, 0.0, Math.sin(index) * wave.current), "entity.lightning_bolt.impact", 3.0f, 0.7f);
	}

	private static void hitVictim(Wave wave, Victim victim, ServerPlayer owner) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		LivingEntity entity = victim.entity();

		if (!entity.isAlive() || entity.isRemoved() || entity.level() != wave.level) {
			return;
		}

		double dx = entity.getX() - wave.center.x;
		double dz = entity.getZ() - wave.center.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		double edge = Math.min(1.0, distance / wave.radius);
		double falloff = 1.0 - (1.0 - cfg.ultimateMinDamageFraction) * edge;
		DamageSource source = owner != null ? wave.level.damageSources().playerAttack(owner) : wave.level.damageSources().generic();

		if (!entity.hurtServer(wave.level, source, (float) (cfg.ultimateDamage * falloff))) {
			return;
		}

		// Stronger the closer to the impact, always outward and upward.
		double knockFalloff = 1.0 - 0.6 * edge;
		double resistance = Math.max(0.0, 1.0 - entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		double length = Math.max(0.001, distance);
		double pushX = distance < 0.01 ? 0.0 : dx / length * cfg.ultimateKnockback * knockFalloff * resistance;
		double pushZ = distance < 0.01 ? 0.0 : dz / length * cfg.ultimateKnockback * knockFalloff * resistance;
		double pushY = cfg.ultimateUpwardKnockback * (0.6 + 0.4 * knockFalloff) * resistance;

		entity.setDeltaMovement(entity.getDeltaMovement().add(pushX, pushY, pushZ));
		entity.hurtMarked = true;
	}

	private static void sampleBand(Wave wave, double inner, double outer, ServerPlayer owner) {
		VoidreaverConfig cfg = VoidreaverConfig.get();

		if (wave.spawned + wave.queue.size() >= cfg.ultimateMaxBlocksTotal || wave.queue.size() >= MAX_QUEUE) {
			return;
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		double area = Math.PI * (outer * outer - inner * inner);
		double expected = area * cfg.ultimateBlockDensity;
		int samples = (int) expected;

		if (random.nextDouble() < expected - samples) {
			samples++;
		}

		samples = Math.min(samples, MAX_SAMPLES_PER_TICK);
		int topY = (int) Math.floor(wave.center.y) + 16;

		for (int i = 0; i < samples; i++) {
			double radius = inner + random.nextDouble() * (outer - inner);
			double angle = random.nextDouble() * Math.PI * 2.0;
			int x = (int) Math.floor(wave.center.x + Math.cos(angle) * radius);
			int z = (int) Math.floor(wave.center.z + Math.sin(angle) * radius);

			// Never load or generate chunks for this: only unloaded columns are skipped.
			if (wave.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
				continue;
			}

			int surface = findSurface(wave.level, x, z, topY);

			if (surface == Integer.MIN_VALUE) {
				continue;
			}

			int depth = 1;

			if (cfg.ultimateSurfaceDepth >= 2 && random.nextDouble() < 0.4) {
				depth = 2;
			}

			if (cfg.ultimateSurfaceDepth >= 3 && depth == 2 && random.nextDouble() < 0.5) {
				depth = 3;
			}

			for (int d = 0; d < depth; d++) {
				wave.queue.add(new BlockPos(x, surface - d, z));
			}
		}
	}

	/** Y of the exposed top block of a column near the impact height, or MIN_VALUE if there is none (air, water, lava). */
	private static int findSurface(ServerLevel level, int x, int z, int topY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, topY, z);
		BlockState start = level.getBlockState(pos);

		if (!start.isAir()) {
			// We started inside terrain (a hill higher than the impact point): walk up to its top.
			for (int y = topY; y < topY + 40; y++) {
				pos.setY(y + 1);

				if (level.getBlockState(pos).isAir()) {
					pos.setY(y);
					BlockState top = level.getBlockState(pos);
					return top.getFluidState().isEmpty() ? y : Integer.MIN_VALUE;
				}
			}

			return Integer.MIN_VALUE;
		}

		for (int y = topY - 1; y > topY - 64; y--) {
			pos.setY(y);
			BlockState state = level.getBlockState(pos);

			if (state.isAir()) {
				continue;
			}

			return state.getFluidState().isEmpty() ? y : Integer.MIN_VALUE;
		}

		return Integer.MIN_VALUE;
	}

	private static void drain(Wave wave, ServerPlayer owner, int now) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		int budget = cfg.ultimateMaxBlocksPerTick;

		while (budget > 0 && !wave.queue.isEmpty() && wave.spawned < cfg.ultimateMaxBlocksTotal && DEBRIS.size() < cfg.ultimateMaxFallingBlocks) {
			BlockPos pos = wave.queue.poll();

			if (launch(wave, owner, pos, now)) {
				wave.spawned++;
				budget--;
			}
		}

		if (wave.spawned >= cfg.ultimateMaxBlocksTotal) {
			wave.queue.clear();
		}
	}

	/** Turns one surface block into a real falling-block entity, if the owner is allowed to and the block is not protected. */
	private static boolean launch(Wave wave, ServerPlayer owner, BlockPos pos, int now) {
		VoidreaverConfig cfg = VoidreaverConfig.get();
		ServerLevel level = wave.level;
		BlockState state = level.getBlockState(pos);

		// Re-check at spawn time: the world may have changed since this position was queued.
		if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0.0f || PROTECTED.matches(state)) {
			return false;
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);

		if (blockEntity != null && !cfg.ultimateBreakBlockEntities) {
			return false;
		}

		if (!owner.getAbilities().mayBuild || !level.mayInteract(owner, pos)) {
			return false;
		}

		if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, owner, pos, state, blockEntity)) {
			return false;
		}

		// fall() removes the original block and spawns the entity in one step, so nothing can be duplicated.
		FallingBlockEntity block = FallingBlockEntity.fall(level, pos.immutable(), state);
		block.disableDrop();

		ThreadLocalRandom random = ThreadLocalRandom.current();
		double dx = pos.getX() + 0.5 - wave.center.x;
		double dz = pos.getZ() + 0.5 - wave.center.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		double nx = distance < 0.01 ? random.nextDouble() - 0.5 : dx / distance;
		double nz = distance < 0.01 ? random.nextDouble() - 0.5 : dz / distance;
		double close = Math.max(0.35, 1.4 - distance / wave.radius);
		double outward = (0.25 + random.nextDouble() * 1.0) * close;
		double swirl = (random.nextDouble() - 0.5) * 0.5;
		double up = (0.45 + random.nextDouble() * 0.95) * close;

		block.setDeltaMovement(nx * outward - nz * swirl, up, nz * outward + nx * swirl);
		block.hurtMarked = true;

		DEBRIS.add(new Debris(block, now + cfg.ultimateDebrisLifetimeTicks));
		return true;
	}

	private static void tickDebris(int now) {
		if (DEBRIS.isEmpty()) {
			return;
		}

		Iterator<Debris> iterator = DEBRIS.iterator();

		while (iterator.hasNext()) {
			Debris debris = iterator.next();

			if (debris.entity.isRemoved()) {
				iterator.remove(); // it landed (and became a block again) or was removed
			} else if (now >= debris.expireTick) {
				debris.entity.discard(); // still airborne after the limit: clean it up instead of leaving it forever
				iterator.remove();
			}
		}
	}
}
