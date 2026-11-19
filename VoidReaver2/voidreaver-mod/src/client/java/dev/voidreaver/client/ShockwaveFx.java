package dev.voidreaver.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.voidreaver.client.fx.Emitters;
import dev.voidreaver.client.fx.Fx;
import dev.voidreaver.net.ShockwavePayload;

/**
 * Client-side visuals of a shockwave (falling smash and Apocalypse Stomp). One packet starts it; from then on the client
 * simulates the front itself. Layers: a ring that follows the real terrain, a trailing second ring, a low dust wave,
 * radial energy streaks, block-coloured debris and floating energy fragments. Camera rumble follows the actual front.
 */
final class ShockwaveFx {
	static final class Wave {
		final int kind;
		final Vec3 center;
		final double maxRadius;
		final double speed;
		final float intensity;
		double radius;
		double prevRadius;
		final int segments;
		final float[] ground = new float[257];
		final float[] groundTrail = new float[257];

		Wave(int kind, Vec3 center, double maxRadius, double speed, float intensity) {
			this.kind = kind;
			this.center = center;
			this.maxRadius = maxRadius;
			this.speed = speed;
			this.intensity = intensity;
			this.segments = (int) Math.max(48, Math.min(256, maxRadius * 2.4));

			for (int i = 0; i < ground.length; i++) {
				ground[i] = (float) center.y;
				groundTrail[i] = (float) center.y;
			}
		}

		/** Ground height of the main ring at an angle, interpolated between the sampled segments. */
		float groundAt(double angle) {
			double wrapped = angle % (Math.PI * 2.0);

			if (wrapped < 0.0) {
				wrapped += Math.PI * 2.0;
			}

			double index = wrapped / (Math.PI * 2.0) * segments;
			int i = (int) index;
			double f = index - i;
			return (float) (ground[i] * (1.0 - f) + ground[i + 1] * f);
		}
	}

	private static final List<Wave> WAVES = new ArrayList<>();

	private ShockwaveFx() { }

	static List<Wave> all() {
		return WAVES;
	}

	static void clear() {
		WAVES.clear();
	}

	static void handle(ShockwavePayload payload) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;

		if (level == null) {
			return;
		}

		boolean ultimate = payload.kind() == ShockwavePayload.ULTIMATE;
		Vec3 center = new Vec3(payload.x(), payload.y(), payload.z());

		if (WAVES.size() < 8) {
			WAVES.add(new Wave(payload.kind(), center, payload.radius(), Math.max(0.2, payload.speed()), payload.intensity()));
		}

		Emitters.impactBurst(level, center, ultimate ? 2.2 : 0.8);

		// Bright flat energy rings that race outwards with the wave.
		if (ultimate) {
			Fx.ring(center.x, center.y + 0.3, center.z, 0, 1, 0, 1.0, 18.0, 1.4, 10, Fx.WHITE_V, Fx.fade(Fx.VIOLET), -1, 0.0);
			Fx.ring(center.x, center.y + 0.3, center.z, 0, 1, 0, 1.0, 34.0, 1.0, 16, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), -1, 0.0);
			Fx.ring(center.x, center.y + 0.3, center.z, 0, 1, 0, 1.0, 58.0, 0.8, 24, Fx.VIOLET, Fx.fade(Fx.VIOLET_DARK), -1, 0.0);
		} else {
			Fx.ring(center.x, center.y + 0.2, center.z, 0, 1, 0, 0.5, 7.0, 0.5, 8, Fx.WHITE_V, Fx.fade(Fx.VIOLET), -1, 0.0);
			Fx.ring(center.x, center.y + 0.2, center.z, 0, 1, 0, 0.5, 12.0, 0.4, 13, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), -1, 0.0);
		}

		if (client.player != null) {
			double distance = client.player.position().distanceTo(center);
			double reach = ultimate ? payload.radius() * 0.9 : payload.radius() * 3.0;
			double falloff = 1.0 - distance / reach;

			if (falloff > 0.0) {
				ScreenShake.add((float) (payload.intensity() * falloff), ultimate ? 30 : 16);
			}

			if (ultimate) {
				ImpactFrame.trigger((float) Math.max(0.0, 1.0 - distance / 70.0));
			} else if (distance < 4.0) {
				ImpactFrame.trigger(0.3f);
			}
		}
	}

	static void tick(Minecraft client) {
		ClientLevel level = client.level;

		if (level == null) {
			WAVES.clear();
			return;
		}

		Iterator<Wave> iterator = WAVES.iterator();

		while (iterator.hasNext()) {
			Wave wave = iterator.next();
			wave.prevRadius = wave.radius;
			wave.radius += wave.speed;

			if (wave.radius > wave.maxRadius + wave.speed * 2.0) {
				iterator.remove();
				continue;
			}

			double radius = Math.min(wave.radius, wave.maxRadius);
			updateGround(level, wave, wave.ground, radius);
			updateGround(level, wave, wave.groundTrail, radius * 0.85);
			spawnLayers(level, client, wave, radius);
			rumble(client, wave, radius);
		}
	}

	private static void updateGround(ClientLevel level, Wave wave, float[] heights, double radius) {
		for (int i = 0; i < wave.segments; i++) {
			double angle = Math.PI * 2.0 * i / wave.segments;
			double x = wave.center.x + Math.cos(angle) * radius;
			double z = wave.center.z + Math.sin(angle) * radius;
			float target = (float) groundY(level, x, z, heights[i]);
			heights[i] += (target - heights[i]) * 0.6f; // smooth, so the ring glides over steps instead of snapping
		}

		heights[wave.segments] = heights[0];
	}

	/** Top solid surface near a hint height. Unloaded terrain simply keeps the hint, so the ring never dives into the void. */
	static double groundY(ClientLevel level, double x, double z, double hint) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int top = (int) Math.floor(hint) + 6;

		for (int y = top; y >= top - 20; y--) {
			pos.set(bx, y, bz);
			BlockState state = level.getBlockState(pos);

			if (!state.getCollisionShape(level, pos).isEmpty()) {
				return y + 1.0;
			}
		}

		return hint;
	}

	private static void spawnLayers(ClientLevel level, Minecraft client, Wave wave, double radius) {
		boolean ultimate = wave.kind == ShockwavePayload.ULTIMATE;
		double px = client.player != null ? client.player.getX() : wave.center.x;
		double pz = client.player != null ? client.player.getZ() : wave.center.z;
		double bearing = Math.atan2(pz - wave.center.z, px - wave.center.x);
		double playerDistance = Math.hypot(px - wave.center.x, pz - wave.center.z);
		boolean concentrate = ultimate && playerDistance < radius + 70.0;

		int dust = Fx.n(ultimate ? 16 : 6);
		int streaks = Fx.n(ultimate ? 8 : 4);
		int debris = Fx.n(ultimate ? 7 : 3);
		int energy = Fx.n(ultimate ? 8 : 4);
		int sparks = Fx.n(ultimate ? 6 : 3);
		int total = dust + streaks + debris + energy + sparks;
		double outward = wave.speed;

		for (int i = 0; i < total; i++) {
			// Spend most of the budget where the player can actually see it.
			double angle = concentrate && Fx.rnd() < 0.8 ? bearing + Fx.rnds() * 1.15 : Fx.rnd() * Math.PI * 2.0;
			double r = radius + Fx.rnds() * wave.speed;
			double x = wave.center.x + Math.cos(angle) * r;
			double z = wave.center.z + Math.sin(angle) * r;
			double y = wave.groundAt(angle);
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			boolean visible = Fx.near(x, y, z, ultimate ? 110.0 : 60.0);
			int ground = Emitters.groundColor(level, x, y, z);

			if (i < dust) {
				if (!visible && Fx.rnd() < 0.7f) {
					continue; // far away only a sparse wall of big dust is needed
				}

				double v = 0.25 + Fx.rnd() * 0.35;
				Fx.spawn(Fx.DUST, x, y + 0.3, z, cos * v * outward * 0.35, 0.02 + Fx.rnd() * 0.08, sin * v * outward * 0.35,
						36 + (int) (Fx.rnd() * 26), 1.3, visible ? 4.6 : 9.0, (ground & 0x00FFFFFF) | 0xB0000000, ground & 0x00FFFFFF, -0.0006, 0.92, (byte) 0);
			} else if (i < dust + streaks) {
				if (!visible) {
					continue;
				}

				Fx.spawn(Fx.STREAK, x, y + 0.3, z, cos * outward * 1.3, 0.01, sin * outward * 1.3,
						6 + (int) (Fx.rnd() * 5), 0.2, 0.02, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), 0.0, 0.96, (byte) 0);
			} else if (i < dust + streaks + debris) {
				if (!visible) {
					continue;
				}

				double v = 0.2 + Fx.rnd() * 0.6;
				Fx.spawn(Fx.DEBRIS, x, y + 0.4, z, cos * v, 0.3 + Fx.rnd() * 0.8, sin * v,
						40 + (int) (Fx.rnd() * 40), 0.18 + Fx.rnd() * 0.14, 0.14, ground, (ground & 0x00FFFFFF) | 0xE0000000, 0.06, 0.98, Fx.COLLIDE);
			} else if (i < dust + streaks + debris + energy) {
				if (!visible) {
					continue;
				}

				Fx.spawn(Fx.ENERGY, x, y + 0.5 + Fx.rnd() * 1.6, z, cos * outward * 0.3, 0.02, sin * outward * 0.3,
						24 + (int) (Fx.rnd() * 18), 0.34, 0.04, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), 0.0, 0.95, (byte) 0);
			} else {
				if (!visible) {
					continue;
				}

				Fx.spawn(Fx.SPARK, x, y + 0.4, z, cos * outward * 0.6, 0.15 + Fx.rnd() * 0.4, sin * outward * 0.6,
						8 + (int) (Fx.rnd() * 8), 0.1, 0.01, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.04, 0.92, (byte) 0);
			}
		}
	}

	/** Ground rumble: strongest as the front passes the player, with a weaker constant tremor while the wave is near. */
	private static void rumble(Minecraft client, Wave wave, double radius) {
		if (client.player == null) {
			return;
		}

		double distance = Math.hypot(client.player.getX() - wave.center.x, client.player.getZ() - wave.center.z);
		double reach = wave.maxRadius * 1.15;

		if (distance > reach + 30.0) {
			return;
		}

		double error = Math.abs(distance - radius);
		double front = Math.exp(-(error * error) / (2.0 * 14.0 * 14.0));
		double base = distance < reach ? (1.0 - distance / reach) * 0.4 : 0.0;
		double scale = wave.kind == ShockwavePayload.ULTIMATE ? 1.0 : 0.5;
		ScreenShake.rumble((float) (wave.intensity * (front * 0.9 + base) * scale));
	}
}
