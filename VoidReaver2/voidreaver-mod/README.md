# THE VOIDREAVER — Fabric mod for Minecraft Java 1.21.11

Legendary long-handled chain mace.

| Input | What it does |
|---|---|
| Right-click | Fires a visible chain from the mace head along your exact look direction, **100 blocks**, ~200 blocks/s. Hooks the first living entity it touches (hook hitbox is **2x** the normal size) and pulls **you** to it at one constant speed, then stops exactly when the two hitboxes touch. **4 second** cooldown. |
| Shift + right-click | **APOCALYPSE STOMP**: charge, sky launch, hang in the air, dive, impact frame, 100-block surface shockwave that throws entities and real falling-block debris. 60 second cooldown, independent of the chain. |
| Left-click | Heavy mace melee: **50 damage** at full charge. |
| Left-click while falling | Falling smash: much more damage (scales with fall distance), huge knockback, plus a shockwave with ring, dust, debris and rumble. |

The chain, the stomp and the melee swing each have their own cooldown. The item is never put on a vanilla item cooldown, so one ability can never block another. Two small bars next to the hotbar show the chain and stomp recharge while you hold the weapon.

## Get the jar without installing anything (GitHub Actions)

Your repository already has the build file. To update it with this version:

1. Unzip `voidreaver-mod.zip`.
2. Open `github.com/YOUR-NAME/Void-reaver/upload/main/voidreaver-mod/voidreaver-mod` (replace YOUR-NAME). This uploads into the folder your earlier files are in, so they get replaced.
3. Drag in everything from inside the unzipped `voidreaver-mod` folder (not the folder itself) and click **Commit changes**.
4. Open the **Actions** tab, wait for the green check, open the run and download **voidreaver-jar** from **Artifacts**.
5. If the run is red, copy the red `error:` lines from the **Build** step and send them back.

## Build locally

JDK 25 is needed to run the build (the Fabric build tool requires it; the mod itself targets Java 21) and internet access.

```
./gradlew build          # Windows: gradlew.bat build
```

The jar is `build/libs/voidreaver-1.0.0.jar` (ignore the `-sources` jar). Put it in `mods` together with **Fabric API 0.141.6+1.21.11**. Get the weapon with `/give @s voidreaver:voidreaver`.

## Config

`config/voidreaver.json` is created on first launch and **updated automatically** by this version (4 s chain cooldown, 100 block range, 2x chain hitbox, 50 melee damage are applied to old files once). After that your edits are kept. Everything is there: ranges, speeds, damage, cooldowns, the chain-breakable block list, the stomp's radius/density/limits/protected blocks, particle quality (`LOW`, `MEDIUM`, `HIGH`, `ULTRA`, client side), screen shake, sound volume.

## What changed in this version

* Constant-speed pull with an exact swept hitbox stop: full speed until the last step, which ends on the target's surface; velocity is then zeroed on the server and the client at once.
* Apocalypse Stomp with server-authoritative state machine, immunity only to what the stomp itself causes (fall, own debris, suffocation, own damage), surface-only block debris as real falling-block entities in capped batches, entity snapshot hit as the front passes, protected-block list, cleanup on death/disconnect/dimension change.
* Custom particle engine (energy, sparks, streaks, dust, debris with collision, rings), terrain-following shockwave rings, ground rumble, impact frame with a visual hit-stop, per-phase stomp effects.
* New long-handled model matching the reference art, a 128px icon that fills the whole hotbar/inventory slot, and a chain-mode model (head glows, chain pulled taut) that the weapon switches to while a chain is out.
* Layered vanilla-only sounds, all ids checked against the real 1.21.11 sound registry.

## Honest status

* **The previous build compiled and ran; this version has not been compiled yet.** Only a syntax check was possible here. The places most likely to need a one-line fix on the first build: `FallingBlockEntity.disableDrop()`, `ServerChunkCache.getChunkNow`, the `CustomModelData` constructor, `BlockState.getMapColor(...).col`.
* The chain-mode model change works by putting a model flag on the held stack, so the first-person weapon may do a short re-draw dip when it switches. It is always cleared when the chain ends, on disconnect and on join.
* The chain-mode model's chain points out of the right-hand side; left-handed holders see it from the other side.
* Not implemented yet (specs received, queued): block grappling, exact vanilla mace mechanics with guaranteed shield break, custom death messages, perfect-timing chain combo with instant chain reset, full player/weapon animations, custom kill effects, the full cinematic screen-effect system (FOV, vignette, chromatic aberration), and the remaining parts of the sound spec (idle/walking chain rattles, falling-block landing sounds, kill-effect audio).
