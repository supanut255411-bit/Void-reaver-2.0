package dev.voidreaver.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import dev.voidreaver.chain.ChainGeometry;
import dev.voidreaver.client.fx.Fx;
import dev.voidreaver.config.VoidreaverConfig;
import dev.voidreaver.net.UltPhasePayload;

/**
 * Apocalypse Stomp visuals around the player, one distinct look per phase, driven by the server's phase events and by the
 * player's real movement: energy gathering at the mace (charge), a rising trail with expanding rings (ascent), orbiting
 * rings and converging energy (suspension) and a long vertical trail that grows with speed (dive). The camera tilts down
 * for the local player so they can see the battlefield and the dive.
 */
final class UltFx {
	private static final class Entry {
		int phase;
		int age;
		Vec3 last;
	}

	private static final Map<Integer, Entry> ACTIVE = new HashMap<>();

	private UltFx() { }

	static void clear() {
		ACTIVE.clear();
	}

	static void handle(UltPhasePayload payload) {
		if (payload.phase() == UltPhasePayload.END) {
			ACTIVE.remove(payload.ownerId());
			return;
		}

		Entry entry = ACTIVE.computeIfAbsent(payload.ownerId(), id -> new Entry());
		entry.phase = payload.phase();
		entry.age = 0;

		Minecraft client = Minecraft.getInstance();

		if (client.level != null && client.level.getEntity(payload.ownerId()) instanceof Player owner) {
			entry.last = owner.position();

			if (payload.phase() == UltPhasePayload.SUSPENSION) {
				// Two tilted energy rings that stay on the player while they hang in the air.
				Fx.ring(owner.getX(), owner.getY(), owner.getZ(), 0.35, 1.0, 0.1, 2.6, 2.6, 0.2, 60, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET), owner.getId(), 1.0);
				Fx.ring(owner.getX(), owner.getY(), owner.getZ(), -0.3, 1.0, -0.2, 3.4, 3.4, 0.16, 60, Fx.WHITE_V, Fx.fade(Fx.VIOLET_LO), owner.getId(), 1.0);
			}

			if (client.player != null && owner.getId() == client.player.getId()) {
				ScreenShake.add(phaseShake(payload.phase()), 10);
			}
		}
	}

	private static float phaseShake(int phase) {
		return switch (phase) {
			case UltPhasePayload.ASCENT -> 0.8f;
			case UltPhasePayload.DESCENT -> 0.5f;
			default -> 0.25f;
		};
	}

	static void tick(Minecraft client) {
		ClientLevel level = client.level;

		if (level == null) {
			ACTIVE.clear();
			return;
		}

		Iterator<Map.Entry<Integer, Entry>> iterator = ACTIVE.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<Integer, Entry> mapEntry = iterator.next();
			Entity entity = level.getEntity(mapEntry.getKey());

			if (!(entity instanceof Player owner) || owner.isRemoved()) {
				iterator.remove();
				continue;
			}

			Entry entry = mapEntry.getValue();
			entry.age++;
			Vec3 now = owner.position();
			Vec3 moved = entry.last == null ? Vec3.ZERO : now.subtract(entry.last);
			entry.last = now;
			double speed = moved.length();
			Vec3 head = ChainGeometry.headPosition(owner, true, 1.0f);

			switch (entry.phase) {
				case UltPhasePayload.CHARGE -> charge(owner, entry, head);
				case UltPhasePayload.ASCENT -> ascent(owner, entry, now, moved, speed);
				case UltPhasePayload.SUSPENSION -> suspension(owner, entry, now, head);
				case UltPhasePayload.DESCENT -> descent(owner, entry, now, moved, speed);
				default -> { }
			}

			// Let the local player see the battlefield below, and keep the weapon pointing at the ground during the dive.
			if (client.player != null && owner.getId() == client.player.getId() && VoidreaverConfig.get().ultimateLookDown
					&& (entry.phase == UltPhasePayload.SUSPENSION || entry.phase == UltPhasePayload.DESCENT)) {
				float pitch = client.player.getXRot();
				float target = entry.phase == UltPhasePayload.DESCENT ? 82.0f : 62.0f;
				client.player.setXRot(pitch + (target - pitch) * 0.18f);
				client.player.xRotO = client.player.getXRot();
			}
		}
	}

	private static void charge(Player owner, Entry entry, Vec3 head) {
		double progress = Math.min(1.0, entry.age / 20.0);

		// Energy gathers into the mace head from every direction, faster and denser the closer to launch.
		for (int i = 0, count = Fx.n(5 + 8 * progress); i < count; i++) {
			Vec3 offset = new Vec3(Fx.rnds(), Fx.rnds(), Fx.rnds()).normalize().scale(2.2 + Fx.rnd() * 2.2);
			Vec3 v = offset.scale(-1.0 / 11.0);
			Fx.spawn(Fx.ENERGY, head.x + offset.x, head.y + offset.y, head.z + offset.z, v.x, v.y, v.z, 11, 0.32, 0.05, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), 0.0, 1.0, (byte) 0);
		}

		Fx.spawn(Fx.FLASH, head.x, head.y, head.z, 0, 0, 0, 2, 0.5 + progress * 1.6, 0.8 + progress * 2.0, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET), 0.0, 1.0, (byte) 0);

		for (int i = 0, count = Fx.n(4); i < count; i++) {
			Fx.spawn(Fx.SPARK, owner.getX() + Fx.rnds() * 0.6, owner.getY() + 0.1, owner.getZ() + Fx.rnds() * 0.6, Fx.rnds() * 0.04, 0.2 + Fx.rnd() * 0.3, Fx.rnds() * 0.04,
					10, 0.08, 0.01, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 0.94, (byte) 0);
		}

		if (entry.age % 4 == 0) {
			Fx.ring(owner.getX(), owner.getY() + 0.1, owner.getZ(), 0, 1, 0, 0.4, 4.0 + progress * 2.0, 0.3, 14, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), -1, 0.0);
		}
	}

	private static void ascent(Player owner, Entry entry, Vec3 now, Vec3 moved, double speed) {
		double strength = Math.min(1.0, entry.age / 12.0);
		Vec3 down = new Vec3(0.0, -1.0, 0.0);

		// A thick trail that stays where the player was, so it follows their real trajectory.
		for (int i = 0, count = Fx.n(8 + 10 * strength + speed * 3.0); i < count; i++) {
			double a = Fx.rnd() * Math.PI * 2.0;
			double r = Fx.rnd() * 0.45;
			Vec3 v = down.scale(0.25 + Fx.rnd() * 0.5 + speed * 0.1);
			Fx.spawn(Fx.STREAK, now.x + Math.cos(a) * r, now.y + Fx.rnd() * 0.5, now.z + Math.sin(a) * r, v.x, v.y, v.z, 12, 0.2, 0.02, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 0.97, (byte) 0);
		}

		for (int i = 0, count = Fx.n(5 + 6 * strength); i < count; i++) {
			double a = entry.age * 0.7 + i * 1.3;
			Vec3 tangent = new Vec3(-Math.sin(a), 0.0, Math.cos(a)).scale(0.18);
			Fx.spawn(Fx.ENERGY, now.x + Math.cos(a) * 1.1, now.y + 0.4 + Fx.rnd() * 1.2, now.z + Math.sin(a) * 1.1, tangent.x, 0.05 + Fx.rnd() * 0.1, tangent.z,
					14, 0.26, 0.04, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), 0.0, 0.96, (byte) 0);
		}

		for (int i = 0, count = Fx.n(4); i < count; i++) {
			Fx.spawn(Fx.SPARK, now.x + Fx.rnds() * 0.4, now.y + 0.4, now.z + Fx.rnds() * 0.4, Fx.rnds() * 0.08, 0.5 + Fx.rnd() * 0.7, Fx.rnds() * 0.08,
					9, 0.08, 0.01, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 0.95, (byte) 0);
		}

		if (entry.age % 3 == 0) {
			Fx.ring(now.x, now.y, now.z, 0, 1, 0, 0.5, 5.0 + 5.0 * strength, 0.35, 14, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), -1, 0.0);
		}

		if (entry.age % 8 == 0) {
			Fx.spawn(Fx.FLASH, now.x, now.y, now.z, 0, 0, 0, 3, 1.6, 3.4, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 1.0, (byte) 0);
		}
	}

	private static void suspension(Player owner, Entry entry, Vec3 now, Vec3 head) {
		double progress = Math.min(1.0, entry.age / 30.0);

		// Compressed energy streams into the weapon, getting denser every tick.
		for (int i = 0, count = Fx.n(8 + 14 * progress); i < count; i++) {
			Vec3 offset = new Vec3(Fx.rnds(), Fx.rnds(), Fx.rnds()).normalize().scale(4.0 + Fx.rnd() * 5.0);
			Vec3 v = offset.scale(-1.0 / 10.0);
			Fx.spawn(Fx.ENERGY, head.x + offset.x, head.y + offset.y, head.z + offset.z, v.x, v.y, v.z, 10, 0.3, 0.05, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET_LO), 0.0, 1.0, (byte) 0);
		}

		for (int i = 0, count = Fx.n(6); i < count; i++) {
			double a = entry.age * 0.45 + i * 1.05;
			double r = 1.6 + progress * 0.8;
			Vec3 tangent = new Vec3(-Math.sin(a), 0.0, Math.cos(a)).scale(0.22);
			Fx.spawn(Fx.ENERGY, now.x + Math.cos(a) * r, now.y + 1.0 + Math.sin(a * 0.5) * 0.6, now.z + Math.sin(a) * r, tangent.x, 0.0, tangent.z, 12, 0.24, 0.04, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 1.0, (byte) 0);
		}

		// Growing glow around the weapon: the aura that is about to be released.
		Fx.spawn(Fx.FLASH, head.x, head.y, head.z, 0, 0, 0, 2, 0.8 + progress * 2.4, 1.2 + progress * 3.0, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET), 0.0, 1.0, (byte) 0);
	}

	private static void descent(Player owner, Entry entry, Vec3 now, Vec3 moved, double speed) {
		double strength = Math.min(1.0, speed / 4.0); // the faster the fall, the denser and longer the trail
		Vec3 up = new Vec3(0.0, 1.0, 0.0);

		for (int i = 0, count = Fx.n(10 + 18 * strength); i < count; i++) {
			double a = Fx.rnd() * Math.PI * 2.0;
			double r = Fx.rnd() * 0.7;
			Vec3 v = up.scale(0.8 + Fx.rnd() * 1.4 + speed * 0.45);
			Fx.spawn(Fx.STREAK, now.x + Math.cos(a) * r, now.y + 0.9 + Fx.rnd(), now.z + Math.sin(a) * r, v.x, v.y, v.z, 10, 0.24, 0.02, Fx.WHITE_V, Fx.fade(Fx.VIOLET_LO), 0.0, 0.98, (byte) 0);
		}

		for (int i = 0, count = Fx.n(6 + 6 * strength); i < count; i++) {
			double a = entry.age * 1.3 + i * 1.7;
			Vec3 tangent = new Vec3(-Math.sin(a), 0.0, Math.cos(a)).scale(0.28);
			Fx.spawn(Fx.SPARK, now.x + Math.cos(a) * 0.9, now.y + 0.5 + Fx.rnd() * 1.4, now.z + Math.sin(a) * 0.9, tangent.x, 0.5 + speed * 0.3, tangent.z,
					8, 0.09, 0.01, Fx.WHITE_V, Fx.fade(Fx.VIOLET), 0.0, 0.96, (byte) 0);
		}

		for (int i = 0, count = Fx.n(3 + 3 * strength); i < count; i++) {
			Vec3 v = new Vec3(Fx.rnds() * 0.1, 0.2 + Fx.rnd() * 0.3, Fx.rnds() * 0.1);
			Fx.spawn(Fx.DEBRIS, now.x + Fx.rnds() * 0.5, now.y + 1.0, now.z + Fx.rnds() * 0.5, v.x, v.y, v.z, 22, 0.16, 0.1, 0xFF2A1450, 0x802A1450, 0.01, 0.97, (byte) 0);
		}

		Fx.spawn(Fx.ENERGY, now.x, now.y + 1.2, now.z, 0, 0.3, 0, 6, 0.6 + strength, 0.2, Fx.VIOLET_HI, Fx.fade(Fx.VIOLET), 0.0, 1.0, (byte) 0);

		if (entry.age % 3 == 0) {
			Fx.ring(now.x, now.y + 2.0, now.z, 0, 1, 0, 1.2, 2.2, 0.15, 6, Fx.WHITE_V, Fx.fade(Fx.VIOLET), -1, 0.0);
		}
	}
}
