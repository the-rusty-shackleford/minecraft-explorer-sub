/*
 * The Explorer - a research submarine for Submersibles.
 * Copyright (C) 2026 Rusty Shackleford and nfx
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.chunkworks.explorersub.gametest;

import com.chunkworks.submersibles.FitOutMenu;
import com.chunkworks.submersibles.Submarine;
import com.chunkworks.submersibles.SubmersiblesContent;
import com.chunkworks.submersibles.Torpedo;
import com.chunkworks.submersibles.client.FitOutScreen;
import com.chunkworks.vanillawheels.Vehicle;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Explorer on film, in a sea pool of a flat world (sand under fourteen blocks of water, dark
 * prismarine round it). In order:
 * <ol>
 * <li>floating at its draft at noon, from the bow and from the stern;</li>
 * <li>a real Left Shift dives it (the booth's player at the controls), and let go it holds its
 *     depth, its screws turning while it goes;</li>
 * <li>through the pilot's eyes: the water ahead through the bubble, not the hull;</li>
 * <li>from a back seat's eye: the water to the side through the bubble;</li>
 * <li>at night, its floods light the water ahead, with and without them;</li>
 * <li>the use key fires a torpedo from the chin;</li>
 * <li>R lets the pilot out at the hatch in the bubble's crown, swimming, and from there, the Explorer at depth;</li>
 * <li>the fit-out's four upgrade slots and two tubes; the hold's three rows.</li>
 * </ol>
 * One {@code booth: PASS} or {@code booth: FAIL} line per check; the Gradle task reads them. Client
 * only, active only under {@code explorer_sub.photobooth}. Silent: the master volume is at nothing
 * from the first tick.
 */
@EventBusSubscriber(modid = GameTestMod.MOD_ID, value = Dist.CLIENT)
public final class ExplorerBooth {
    private ExplorerBooth() {}

    private static final Logger LOG = LoggerFactory.getLogger("Explorer booth");
    private static final boolean ACTIVE = Boolean.getBoolean("explorer_sub.photobooth");
    private static final ResourceLocation EXPLORER = ResourceLocation.fromNamespaceAndPath("explorer_sub", "explorer");
    private static final double DRAFT = 0.40;

    private enum Phase { TITLE, LOADING, PLACING, RUNNING, DONE }

    private record Step(int at, Runnable action) {}

    private static final int HOLD = 100;
    /** The pool's inside: x and z 0..SIZE-1, DEPTH blocks of water over its sand. */
    private static final int SIZE = 40, DEPTH = 14;

    private static boolean muted = false;
    private static Phase phase = Phase.TITLE;
    private static int tick = 0;
    private static List<Step> steps;
    private static UUID subId;
    private static double ground;
    private static float brightOn = -1.0f;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ACTIVE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!muted) {
            mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
            muted = true;
        }
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    phase = Phase.LOADING;
                    createWorld(mc);
                }
            }
            case LOADING -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (mc.level != null && mc.player != null && mc.screen == null && server != null
                        && mc.level.hasChunkAt(mc.player.blockPosition())) {
                    phase = Phase.PLACING;
                    mc.options.hideGui = true;
                    steps = plan(mc);
                    onServer(mc, ExplorerBooth::setUp);
                }
            }
            case PLACING -> {
                if (mc.player != null && client(mc) != null) {
                    phase = Phase.RUNNING;
                    tick = 0;
                }
            }
            case RUNNING -> {
                for (Step step : steps) {
                    if (step.at() == tick) {
                        step.action().run();
                    }
                }
                tick++;
            }
            case DONE -> { }
        }
    }

    private static void createWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        LevelSettings settings = new LevelSettings("Explorer booth", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(1L, false, false);
        mc.createWorldOpenFlows().createFreshLevel("explorer-booth", settings, options,
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                mc.screen);
    }

    /** Noon; the pool dug and filled; the Explorer set down floating at its draft, facing south down the pool. */
    private static void setUp(ServerPlayer sp) {
        ServerLevel level = sp.serverLevel();
        level.setDayTime(6000L);
        ground = level.getMinBuildHeight() + 4;
        int g = (int) ground;
        for (int x = -1; x <= SIZE; x++) {
            for (int z = -1; z <= SIZE; z++) {
                boolean wall = x == -1 || z == -1 || x == SIZE || z == SIZE;
                level.setBlock(new BlockPos(x, g - 1, z), wall ? Blocks.DARK_PRISMARINE.defaultBlockState() : Blocks.SAND.defaultBlockState(), 2);
                for (int y = g; y < g + DEPTH + 1; y++) {
                    level.setBlock(new BlockPos(x, y, z), wall ? Blocks.DARK_PRISMARINE.defaultBlockState()
                            : y < g + DEPTH ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // A spectator for the opening shots, which never falls: a creative flyer told to fly as it was moved
        // could take the flying while it still stood at the world's spawn, lose it on its next step (the game
        // lands a flyer that touches the ground) and drop into the pool, the camera under water. Boarding
        // makes it a survival player.
        sp.setGameMode(GameType.SPECTATOR);
        sp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        double surface = g + DEPTH - 1 + 0.888;
        Vehicle v = Vehicle.create(level, EXPLORER, new Vec3(SIZE / 2.0, surface - DRAFT * 2.20, 12.5), 0.0f);
        if (!(v instanceof Submarine s)) {
            LOG.error("booth: FAIL the Explorer is a submarine -- {}", v);
            throw new IllegalStateException("no submarine");
        }
        s.setFuel(s.tank().capacity());
        level.addFreshEntity(s);
        subId = s.getUUID();
        aim(sp, s.position().add(3.6, 3.2, 4.6), s.position().add(0.0, 1.1, 0.3));   // the player's feet well over the water
    }

    /** effects: puts the booth's player at {@code from}, looking at {@code at} */
    private static void aim(ServerPlayer sp, Vec3 from, Vec3 at) {
        Vec3 d = at.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        sp.teleportTo(sp.serverLevel(), from.x, from.y - sp.getEyeHeight(), from.z, yaw, pitch);
    }

    /** effects: turns the client's own view to {@code yaw} degrees off the submarine's heading and {@code pitch} down */
    private static void look(Minecraft mc, float yaw, float pitch) {
        Submarine c = client(mc);
        if (c != null && mc.player != null) {
            float y = c.getYRot() + yaw;
            mc.player.setYRot(y);
            mc.player.yRotO = y;
            mc.player.setYHeadRot(y);
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
    }

    private static List<Step> plan(Minecraft mc) {
        List<Step> s = new ArrayList<>();
        int t = HOLD;
        double[] y = new double[2];
        double[] spin = new double[1];
        s.add(new Step(t, () -> {
            Submarine c = client(mc);
            double wet = c == null ? -1.0 : c.submersion();
            verdict("the Explorer floats at its draft", () -> Math.abs(wet - DRAFT) <= 0.1 ? null : "under water " + wet);
            int white = count(mc, ExplorerBooth::white);
            shoot(mc, "explorer-floating");
            verdict("its white hull shows", () -> white > 2000 ? null : "white pixels " + white);
            onServer(mc, sp -> server(sp).ifPresent(sub -> aim(sp, sub.position().add(-3.6, 3.2, -4.8), sub.position().add(0.0, 1.0, -0.9))));
        }));
        s.add(new Step(t += 20, () -> {
            shoot(mc, "explorer-floating-stern");
            onServer(mc, sp -> {
                sp.setGameMode(GameType.SURVIVAL);
                server(sp).ifPresent(sub -> sp.startRiding(sub, true));
            });
        }));
        s.add(new Step(t += 20, () -> {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            Submarine c = client(mc);
            verdict("the booth's player takes the controls", () -> c != null && c.getControllingPassenger() == mc.player ? null : "riding " + mc.player.getVehicle());
            look(mc, 0.0f, 20.0f);
            y[0] = c == null ? Double.NaN : c.getY();
            spin[0] = c == null ? Double.NaN : c.propellerAngle(1.0f);
            xkey("down", "Shift_L");
        }));
        s.add(new Step(t += 40, () -> shoot(mc, "explorer-diving")));
        s.add(new Step(t += 30, () -> {
            Submarine c = client(mc);
            double dy = c == null ? Double.NaN : c.getY() - y[0];
            verdict("a real Left Shift dives it", () -> dy < -3.0 ? null : "moved " + dy);
            verdict("Shift held, the pilot stays aboard", () -> mc.player.getVehicle() == c ? null : "riding " + mc.player.getVehicle());
            double turned = c == null ? Double.NaN : Math.abs(c.propellerAngle(1.0f) - spin[0]);
            verdict("its screws turn while it goes", () -> turned > 0.5 ? null : "turned " + turned);
            xkey("up", "Shift_L");
        }));
        s.add(new Step(t += 30, () -> {
            Submarine c = client(mc);
            y[1] = c == null ? Double.NaN : c.getY();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            look(mc, 0.0f, 5.0f);
        }));
        s.add(new Step(t += 40, () -> {
            Submarine c = client(mc);
            double dy = c == null ? Double.NaN : c.getY() - y[1];
            verdict("let go, it holds its depth", () -> Math.abs(dy) < 0.3 ? null : "moved " + dy);
            verdict("the pilot breathes at depth", () -> mc.player.isEyeInFluid(FluidTags.WATER)
                    && mc.player.getAirSupply() >= mc.player.getMaxAirSupply() - 2 ? null : "air " + mc.player.getAirSupply());
            int water = count(mc, ExplorerBooth::waterBlue);
            shoot(mc, "explorer-pilot-view");
            verdict("the pilot sees the water ahead through the bubble", () -> water > 0.5 * frame(mc) ? null : "water-blue pixels " + water + " of " + frame(mc));
            // The back row's left seat's eye, out of the bubble's side: the booth has one player, and the server seats a
            // boarding player ahead of anyone at the controls, so the camera stands where that rider's eye is put instead.
            onServer(mc, sp -> server(sp).ifPresent(sub -> {
                sp.stopRiding();
                sp.setGameMode(GameType.CREATIVE);
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                Vec3 eye = sub.position().add(sub.rotate(sub.profile().localBlocks(sub.profile().seats().get(2).eye().orElseThrow())));
                Vec3 left = sub.rotate(new com.chunkworks.vanillawheels.domain.Vec(1.0, 0.0, 0.0));
                aim(sp, eye, eye.add(left.scale(4.0)).add(0.0, -0.6, 0.0));
            }));
        }));
        s.add(new Step(t += 20, () -> {
            int water = count(mc, ExplorerBooth::waterBlue);
            shoot(mc, "explorer-back-seat-view");
            verdict("from a back seat's eye, the water to the side through the bubble", () -> water > 0.35 * frame(mc) ? null : "water-blue pixels " + water + " of " + frame(mc));
            onServer(mc, sp -> server(sp).ifPresent(sub -> {
                sp.setGameMode(GameType.SURVIVAL);
                sp.startRiding(sub, true);
                sp.serverLevel().setDayTime(18000L);
            }));
        }));
        s.add(new Step(t += 20, () -> look(mc, 0.0f, 10.0f)));
        s.add(new Step(t += 40, () -> onServer(mc, sp -> server(sp).ifPresent(sub -> lights(sub, Vehicle.Lights.ON)))));
        s.add(new Step(t += 40, () -> {
            brightOn = brightness(mc);
            shoot(mc, "explorer-floods-on");
            onServer(mc, sp -> server(sp).ifPresent(sub -> lights(sub, Vehicle.Lights.OFF)));
        }));
        s.add(new Step(t += 40, () -> {
            float off = brightness(mc);
            shoot(mc, "explorer-floods-off");
            verdict("at night its floods light the water ahead", () -> brightOn > off * 1.3f + 2.0f ? null : "brightness on " + brightOn + ", off " + off);
            onServer(mc, sp -> server(sp).ifPresent(sub -> {
                lights(sub, Vehicle.Lights.ON);
                sub.fitOut().setItem(sub.weaponSlot(0), new ItemStack(SubmersiblesContent.TORPEDO_TUBE.get()));
                sub.fitOut().setItem(sub.weaponSlot(1), new ItemStack(SubmersiblesContent.TORPEDO_TUBE.get()));
                sub.getItemStacks().set(0, new ItemStack(SubmersiblesContent.TORPEDO_ITEM.get(), 4));
            }));
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            look(mc, 0.0f, 15.0f);
        }));
        s.add(new Step(t += 40, () -> {
            shoot(mc, "explorer-floods");
            KeyMapping.click(mc.options.keyUse.getKey());
        }));
        s.add(new Step(t += 6, () -> {
            int torpedoes = torpedoes(mc);
            shoot(mc, "explorer-torpedo");
            verdict("the use key at the controls fires a torpedo", () -> torpedoes == 1 ? null : "torpedoes under way " + torpedoes);
            onServer(mc, sp -> sp.serverLevel().setDayTime(6000L));
            xkey("down", "r");
        }));
        s.add(new Step(t += 4, () -> xkey("up", "r")));
        s.add(new Step(t += 20, () -> {
            Submarine c = client(mc);
            verdict("R lets the pilot out", () -> mc.player.getVehicle() == null ? null : "riding " + mc.player.getVehicle());
            Vec3 hatch = c == null ? Vec3.ZERO : c.position().add(c.rotate(c.profile().localBlocks(c.submarine().hatch().orElseThrow())));
            double off = mc.player.position().distanceTo(hatch);
            verdict("at the hatch in the bubble's crown", () -> off < 1.5 ? null : "off by " + off);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            onServer(mc, sp -> {
                sp.setGameMode(GameType.CREATIVE);
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                server(sp).ifPresent(sub -> aim(sp, sub.position().add(6.5, 1.4, 4.0), sub.position().add(0.0, 1.0, 0.2)));
            });
        }));
        s.add(new Step(t += 30, () -> {
            shoot(mc, "explorer-at-depth");
            onServer(mc, sp -> server(sp).ifPresent(sub -> {
                sub.fitOut().setItem(sub.paintSlot(), ItemStack.EMPTY);
                aim(sp, sub.position().add(-4.0, 3.0, 2.0), sub.position().add(0.0, 1.2, 0.0));   // within the eight blocks a menu stays open
                sub.openFitOut(sp);
            }));
        }));
        s.add(new Step(t += 30, () -> {
            boolean open = mc.screen instanceof FitOutScreen;
            FitOutMenu menu = open ? ((FitOutScreen) mc.screen).getMenu() : null;
            verdict("the fit-out opens", () -> open ? null : "screen " + mc.screen);
            verdict("with four upgrade slots and two tubes", () -> menu != null && menu.upgrades() == 4 && menu.weapons() == 2
                    ? null : menu == null ? "no menu" : "upgrades " + menu.upgrades() + ", weapons " + menu.weapons());
            shoot(mc, "explorer-fit-out");
            onServer(mc, sp -> {
                sp.closeContainer();
                server(sp).ifPresent(sub -> sub.openChest(sp, 0));
            });
        }));
        s.add(new Step(t += 30, () -> {
            boolean open = mc.screen instanceof ContainerScreen cs && cs.getMenu().getRowCount() == 3;
            verdict("the hold opens, three rows", () -> open ? null : "screen " + mc.screen);
            shoot(mc, "explorer-hold");
            onServer(mc, ServerPlayer::closeContainer);
        }));
        s.add(new Step(t += 20, () -> {
            LOG.info("booth: PASS all checks ran");
            phase = Phase.DONE;
            mc.stop();
        }));
        return s;
    }

    /** effects: switches {@code sub}'s lamps round to {@code want} */
    private static void lights(Submarine sub, Vehicle.Lights want) {
        for (int i = 0; i < Vehicle.Lights.values().length && sub.lights() != want; i++) {
            sub.cycleLights();
        }
    }

    /** effects: a white or pale grey pixel: the hull's paint by day */
    private static boolean white(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return r > 150 && g > 150 && b > 140 && Math.abs(r - b) < 40 && Math.abs(r - g) < 25;
    }

    /** effects: a pixel of water seen at a distance, bright or deep: blue half again over red and over green; the hull's grey is not */
    private static boolean waterBlue(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return b > r * 3 / 2 + 10 && b >= g;
    }

    /** effects: returns how many pixels the frame's middle (rows 15..85 %, columns 10..90 %) has */
    private static int frame(Minecraft mc) {
        int w = mc.getMainRenderTarget().width, h = mc.getMainRenderTarget().height;
        return ((int) (h * .85) - (int) (h * .15)) * ((int) (w * .9) - (int) (w * .1));
    }

    /** effects: returns how many pixels of the frame's middle (rows 15..85 %, columns 10..90 %) satisfy {@code test} */
    private static int count(Minecraft mc, IntPredicate test) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int n = 0;
            int w = image.getWidth(), h = image.getHeight();
            for (int y = (int) (h * .15); y < (int) (h * .85); y++) {
                for (int x = (int) (w * .1); x < (int) (w * .9); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    if (test.test((abgr & 0xFF) << 16 | (abgr >> 8 & 0xFF) << 8 | (abgr >> 16 & 0xFF))) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    /** effects: returns the mean brightness, 0..255, of the frame's middle third: what lamps ahead of a submarine light */
    private static float brightness(Minecraft mc) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            long sum = 0, n = 0;
            int w = image.getWidth(), h = image.getHeight();
            for (int y = h / 3; y < 2 * h / 3; y++) {
                for (int x = w / 3; x < 2 * w / 3; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    sum += (abgr & 0xFF) + (abgr >> 8 & 0xFF) + (abgr >> 16 & 0xFF);
                    n++;
                }
            }
            return n == 0 ? 0.0f : sum / (3.0f * n);
        }
    }

    // --- plumbing --------------------------------------------------------

    /** effects: returns the client's submarine, or null */
    @org.jetbrains.annotations.Nullable
    private static Submarine client(Minecraft mc) {
        if (mc.level == null || subId == null) {
            return null;
        }
        for (var e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(subId) && e instanceof Submarine s) {
                return s;
            }
        }
        return null;
    }

    /** effects: returns how many torpedoes this client sees under way */
    private static int torpedoes(Minecraft mc) {
        int n = 0;
        if (mc.level != null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof Torpedo) {
                    n++;
                }
            }
        }
        return n;
    }

    /** effects: returns the server's submarine, if it is there */
    private static Optional<Submarine> server(ServerPlayer sp) {
        return sp.serverLevel().getEntity(subId) instanceof Submarine s ? Optional.of(s) : Optional.empty();
    }

    private static void onServer(Minecraft mc, Consumer<ServerPlayer> action) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null) {
                action.accept(sp);
            }
        });
    }

    /**
     * effects: starts devtools/booth/xkey.py pressing ({@code down}) or letting go ({@code up}) of
     * {@code keysym} on this client's display, and returns it; null, with a FAIL line, if it would
     * not start. The helper refuses a display with a window manager, so it never types on a desktop.
     */
    @org.jetbrains.annotations.Nullable
    private static Process xkey(String action, String keysym) {
        Path script = Path.of(System.getProperty("user.dir"), "..", "..", "devtools", "booth", "xkey.py").normalize();
        Path uv = Path.of(System.getProperty("user.home"), ".local", "bin", "uv");
        try {
            return new ProcessBuilder(Files.isExecutable(uv) ? uv.toString() : "uv", "run", "--no-project", "--with", "python-xlib",
                    "python", script.toString(), action, keysym).inheritIO().start();
        } catch (IOException e) {
            LOG.error("booth: FAIL the key helper starts ({} {}) -- {}", action, keysym, e.toString());
            return null;
        }
    }

    private static void shoot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                message -> LOG.info("booth: {}", message.getString()));
    }

    /** Runs {@code check}; null is a pass, anything else the failure's detail. */
    private static void verdict(String what, Supplier<String> check) {
        String detail;
        try {
            detail = check.get();
        } catch (RuntimeException e) {
            detail = e.toString();
        }
        if (detail == null) {
            LOG.info("booth: PASS {}", what);
        } else {
            LOG.error("booth: FAIL {} -- {}", what, detail);
        }
    }
}
