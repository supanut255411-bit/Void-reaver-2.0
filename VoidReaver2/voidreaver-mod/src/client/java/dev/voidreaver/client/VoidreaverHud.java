package dev.voidreaver.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import dev.voidreaver.ModItems;

/**
 * HUD layer: the impact-frame overlay, and two small recharge bars (chain and Apocalypse Stomp) next to the hotbar while the
 * weapon is held. The item itself is never put on a vanilla cooldown, so each ability recharges independently.
 */
final class VoidreaverHud {
	private VoidreaverHud() { }

	static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) {
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		float partial = tickCounter.getGameTimeDeltaPartialTick(false);
		int overlay = ImpactFrame.overlay(partial);

		if (overlay != 0) {
			graphics.fill(0, 0, width, height, overlay);
		}

		boolean holding = client.player.getMainHandItem().is(ModItems.VOIDREAVER) || client.player.getOffhandItem().is(ModItems.VOIDREAVER);

		if (!holding) {
			return;
		}

		int x = width / 2 + 112;
		bar(graphics, client, x, height - 50, "CHAIN", ClientCooldowns.chainLeft, ClientCooldowns.chainTotal);
		bar(graphics, client, x, height - 33, "STOMP", ClientCooldowns.ultLeft, ClientCooldowns.ultTotal);
	}

	private static void bar(GuiGraphics graphics, Minecraft client, int x, int y, String label, int left, int total) {
		int barWidth = 62;
		boolean ready = left <= 0;
		double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() * 0.008);
		int fillColor;

		if (ready) {
			int shade = (int) (170 + 85 * pulse);
			fillColor = 0xFF000000 | (shade << 16) | (Math.min(255, shade - 60) << 8) | 255;
		} else {
			fillColor = 0xFF6A2BD0;
		}

		double fraction = ready ? 1.0 : 1.0 - (double) left / total;
		int filled = (int) Math.round(barWidth * Math.max(0.0, Math.min(1.0, fraction)));
		String text = ready ? label + " READY" : String.format("%s %.1fs", label, left / 20.0);

		graphics.drawString(client.font, text, x, y, ready ? 0xFFE8D4FF : 0xFFB9A3DD, true);
		graphics.fill(x - 1, y + 10, x + barWidth + 1, y + 16, 0xC0100818);
		graphics.fill(x, y + 11, x + filled, y + 15, fillColor);
	}
}
