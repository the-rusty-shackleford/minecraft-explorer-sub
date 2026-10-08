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

import com.chunkworks.submersibles.Submarine;
import com.chunkworks.submersibles.SubmersiblesContent;
import com.chunkworks.submersibles.Torpedo;
import com.chunkworks.submersibles.api.SubmarineProfile;
import com.chunkworks.submersibles.api.Submersibles;
import com.chunkworks.submersibles.domain.DiveInput;
import com.chunkworks.vanillawheels.ModContent;
import com.chunkworks.vanillawheels.Vehicle;
import com.chunkworks.vanillawheels.api.VanillaWheels;
import com.chunkworks.vanillawheels.api.VehicleProfile;
import com.chunkworks.vanillawheels.domain.Condition;
import com.chunkworks.vanillawheels.domain.Vec;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Explorer on a headless server, in a tank of water 48 long, 24 wide, walled with barriers:
 * its two profiles make it a submarine of four seats on skids, with a hold of three rows, twin
 * floods, two screws, four upgrade slots and two tubes; its hull crafts at a crafting table and,
 * with an engine, makes the packed submarine, which set down on open water floats at its draft;
 * it holds its depth and rises to float; it runs up to its own top speed; four ride and breathe at
 * depth and a fifth is turned away; its hold opens through the deck hatch and from a seat; and a
 * torpedo leaves each tube under the chin ahead of the bow.
 */
@GameTestHolder("explorer_sub")
@PrefixGameTestTemplate(false)
public final class ExplorerGameTests {
    private static final ResourceLocation EXPLORER = ResourceLocation.fromNamespaceAndPath("explorer_sub", "explorer");
    /** The tank (devtools/gameteststructures/tank.snbt), in the test's own blocks: x along it, z across. */
    private static final int LENGTH = 48, HEIGHT = 24, WIDTH = 24;
    /** The pool's water fills y 1 to POOL; its surface is at POOL + 1. */
    private static final int POOL = 14;
    /** Its profile's numbers: thrust 0.011, the propeller's slip 0.016 and the hull's drag 0.032. */
    private static final double TOP_SPEED = 0.011 * (1.0 - 0.048) / 0.048;
    private static final double DRAFT = 0.40;

    public ExplorerGameTests() {}

    // ------------------------------------------------------------------ the rigs

    /** effects: lays the tank's stone floor (y 0) and fills it with water from y 1 up to {@code depth}, wall to wall */
    private static void pool(GameTestHelper helper, int depth) {
        for (int x = 0; x < LENGTH; x++) {
            for (int z = 0; z < WIDTH; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= depth; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
            }
        }
    }

    /** effects: returns a fuelled Explorer at (x, y, z) facing {@code yaw}, in the level */
    private static Submarine sub(GameTestHelper helper, double x, double y, double z, float yaw) {
        Vehicle v = Vehicle.create(helper.getLevel(), EXPLORER, helper.absoluteVec(new Vec3(x, y, z)), yaw);
        helper.assertTrue(v instanceof Submarine, "the Explorer is made as Submersibles' submarine: " + v);
        v.setFuel(v.tank().capacity());
        helper.getLevel().addFreshEntity(v);
        return (Submarine) v;
    }

    /** effects: puts an armor stand at {@code s}'s controls -- a pilot that is no player, so the server dives by the script -- and returns it */
    private static ArmorStand standIn(GameTestHelper helper, Submarine s) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.assertTrue(stand != null, "an armor stand");
        stand.setPos(s.getX(), s.getY(), s.getZ());
        helper.getLevel().addFreshEntity(stand);
        helper.assertTrue(stand.startRiding(s, true), "the stand-in takes the controls");
        return stand;
    }

    /** effects: returns the script's input: thrust, rudder and planes, powered, the world's facts filled in each tick */
    private static DiveInput dive(int forward, int turn, int lift) {
        return new DiveInput(forward, turn, lift, true, true, 1.0, false, 0.0, 0.0, 0.0);
    }

    /** effects: returns a villager with no AI at {@code at} (absolute), in the level: ticked as every mob is, so it can run out of air */
    private static Villager villager(GameTestHelper helper, Vec3 at) {
        Villager v = EntityType.VILLAGER.create(helper.getLevel());
        helper.assertTrue(v != null, "a villager");
        v.setNoAi(true);
        v.setPos(at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(v);
        return v;
    }

    /** A server player with a connection that goes nowhere, so menus and item use take the real path. */
    private static ServerPlayer player(GameTestHelper helper, String name, Vec3 at) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer sp = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, sp, cookie);
        sp.setGameMode(GameType.SURVIVAL);
        Vec3 abs = helper.absoluteVec(at);
        sp.teleportTo(abs.x, abs.y, abs.z);
        return sp;
    }

    private static void logOff(ServerPlayer sp) {
        sp.connection.disconnect(net.minecraft.network.chat.Component.literal("test complete"));
    }

    /** effects: returns the crafting grid's result recipe, if any, for {@code items} laid row by row in a {@code w} by {@code h} grid */
    private static Optional<RecipeHolder<CraftingRecipe>> craft(GameTestHelper helper, int w, int h, List<ItemStack> items) {
        return helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(w, h, items), helper.getLevel());
    }

    /** effects: returns the packed Explorer, crafted as a player would: the hull, then the hull and an engine */
    private static ItemStack craftedExplorer(GameTestHelper helper) {
        Item steel = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("metalsandmaterials", "steel_block"));
        helper.assertTrue(steel != Items.AIR, "Metals and Materials' steel block is here");
        List<ItemStack> grid = new ArrayList<>();
        for (Item item : new Item[] {steel, Items.GLASS, steel, Items.GLASS, Items.CHEST, Items.GLASS, steel, steel, steel}) {
            grid.add(new ItemStack(item));
        }
        Optional<RecipeHolder<CraftingRecipe>> hull = craft(helper, 3, 3, grid);
        helper.assertTrue(hull.isPresent(), "steel round three windows and a chest craft something");
        helper.assertValueEqual(hull.get().id(), ResourceLocation.fromNamespaceAndPath("explorer_sub", "explorer_chassis"), "the Explorer's hull");
        ItemStack chassis = hull.get().value().assemble(CraftingInput.of(3, 3, grid), helper.getLevel().registryAccess());
        helper.assertTrue(chassis.is(ModContent.CHASSIS.get()) && VanillaWheels.vehicleOf(chassis).equals(Optional.of(EXPLORER)), "a chassis for the Explorer: " + chassis);
        List<ItemStack> pair = List.of(chassis, new ItemStack(ModContent.ENGINE.get()));
        Optional<RecipeHolder<CraftingRecipe>> whole = craft(helper, 2, 1, pair);
        helper.assertTrue(whole.isPresent(), "the hull and an engine craft something");
        helper.assertValueEqual(whole.get().id(), EXPLORER, "the Explorer itself");
        return whole.get().value().assemble(CraftingInput.of(2, 1, pair), helper.getLevel().registryAccess());
    }

    // ------------------------------------------------------------------ the tests

    @GameTest(template = "tank", timeoutTicks = 40)
    public void theProfilesMakeItASubmarineOfFourSeatsOnSkids(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        VehicleProfile p = VanillaWheels.profile(registries, EXPLORER).map(h -> h.value()).orElse(null);
        helper.assertTrue(p != null, "explorer_sub:explorer is a vehicle");
        helper.assertValueEqual(p.seats().size(), 4, "a pilot and three");
        helper.assertTrue(p.seats().get(0).driver(), "the pilot's seat first");
        helper.assertTrue(p.seats().stream().allMatch(s -> s.eye().isPresent()), "every rider's eye placed at a window");
        helper.assertTrue(!p.wheels().drawn() && p.wheels().positions().size() == 4, "resting on its skids' ends");
        helper.assertValueEqual(p.storage().orElseThrow().chests().size(), 1, "one hold");
        helper.assertValueEqual(p.storage().orElseThrow().chests().get(0).rows(), 3, "of three rows");
        helper.assertValueEqual(p.headlights().orElseThrow().at().size(), 2, "twin floods");
        helper.assertValueEqual(p.headlights().orElseThrow().range(), 24, "reaching 24 blocks");
        helper.assertValueEqual(p.paint().orElseThrow().factory(), Optional.of(0xe9e8e1), "white from the factory");
        helper.assertValueEqual(p.sounds().engine(), Optional.of(ResourceLocation.fromNamespaceAndPath("explorer_sub", "engine")), "its own loop");
        SubmarineProfile sp = Submersibles.submarine(registries, EXPLORER).orElse(null);
        helper.assertTrue(sp != null, "and a submarine");
        helper.assertValueEqual(sp.upgrades(), 4, "four upgrade slots");
        helper.assertValueEqual(sp.weapons().size(), 2, "two tubes");
        helper.assertValueEqual(sp.propellers().size(), 2, "two screws");
        helper.assertTrue(sp.propellers().get(0).speed() == -sp.propellers().get(1).speed(), "turning opposite ways");
        helper.assertTrue(sp.hatch().isPresent(), "a hatch to get out at");
        helper.assertValueEqual(sp.properties().durability(), 4.0, "tough: a quarter of the wear");
        Submarine s = sub(helper, 24.5, 2, 12.5, 0.0f);
        helper.assertValueEqual(s.getName().getString(), "Explorer", "named");
        helper.assertTrue(Math.abs(s.hullform().topSpeed() - TOP_SPEED) < 1e-9, "its top speed by its numbers: " + s.hullform().topSpeed());
        helper.succeed();
    }

    @GameTest(template = "tank", timeoutTicks = 40)
    public void itsHullCraftsAtACraftingTableAndWithAnEngineMakesThePackedSubmarine(GameTestHelper helper) {
        ItemStack packed = craftedExplorer(helper);
        helper.assertTrue(packed.is(ModContent.VEHICLE_ITEM.get()), "a packed vehicle: " + packed);
        helper.assertValueEqual(VanillaWheels.vehicleOf(packed), Optional.of(EXPLORER), "the Explorer");
        // Every vehicle's hull is the same item: only the Explorer's makes an Explorer.
        ItemStack other = ModContent.chassisStack(ResourceLocation.fromNamespaceAndPath("explorer_sub", "not_the_explorer"));
        Optional<RecipeHolder<CraftingRecipe>> wrong = craft(helper, 2, 1, List.of(other, new ItemStack(ModContent.ENGINE.get())));
        helper.assertTrue(wrong.isEmpty(), "another vehicle's hull and an engine make nothing here: " + wrong.map(RecipeHolder::id));
        helper.succeed();
    }

    @GameTest(template = "tank", timeoutTicks = 300)
    public void aCraftedExplorerSetDownOnOpenWaterFloatsAtItsDraft(GameTestHelper helper) {
        pool(helper, POOL);
        // Over open water, for the tick it takes: a sub set down beside a dock has its tail under the dock.
        ServerPlayer sp = player(helper, "launcher", new Vec3(24.5, POOL + 2, 2.5));
        sp.setYRot(0.0f);   // facing +z, down at the water 4.4 blocks off, within reach
        sp.setXRot(55.0f);
        sp.setItemInHand(InteractionHand.MAIN_HAND, craftedExplorer(helper));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    sp.gameMode.useItem(sp, helper.getLevel(), sp.getMainHandItem(), InteractionHand.MAIN_HAND);
                    helper.assertTrue(sp.getMainHandItem().isEmpty(), "the item was used up");
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    List<Submarine> subs = helper.getLevel().getEntitiesOfClass(Submarine.class, new AABB(sp.blockPosition()).inflate(10.0));
                    helper.assertValueEqual(subs.size(), 1, "one Explorer set down on the water");
                    double wet = subs.get(0).submersion();
                    helper.assertTrue(Math.abs(wet - DRAFT) <= 0.08, "floating at its draft: " + wet + " under water");
                    logOff(sp);
                })
                .thenSucceed();
    }

    @GameTest(template = "tank", timeoutTicks = 500)
    public void itHoldsItsDepthThenRisesToFloatAtItsDraft(GameTestHelper helper) {
        pool(helper, POOL);
        Submarine s = sub(helper, 24.5, 4, 12.5, 0.0f);
        standIn(helper, s);
        s.setScriptedDive(dive(0, 0, 0));
        double[] y0 = new double[1];
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> y0[0] = s.getY())
                .thenIdle(100)
                .thenExecute(() -> {
                    helper.assertTrue(Math.abs(s.getY() - y0[0]) <= 0.1, "it held its depth: " + (s.getY() - y0[0]));
                    s.setScriptedDive(dive(0, 0, 1));
                })
                .thenIdle(300)
                .thenExecute(() -> {
                    double wet = s.submersion();
                    helper.assertTrue(Math.abs(wet - DRAFT) <= 0.08, "up, it floats at its draft: " + wet);
                    helper.assertTrue(Math.abs(s.getDeltaMovement().y) <= 0.02, "and stays: " + s.getDeltaMovement().y);
                })
                .thenSucceed();
    }

    @GameTest(template = "tank", timeoutTicks = 500)
    public void aheadItRunsUpToItsOwnTopSpeed(GameTestHelper helper) {
        pool(helper, POOL);
        Submarine s = sub(helper, 6.5, 5, 12.5, -90.0f);   // facing +x, down the tank
        standIn(helper, s);
        s.setScriptedDive(dive(1, 0, 0));
        double startX = helper.absoluteVec(new Vec3(6.5, 0, 0)).x;
        Vec3[] from = new Vec3[1];
        StringBuilder trace = new StringBuilder();
        int[] tick = {0};
        helper.onEachTick(() -> {
            if (tick[0]++ % 20 == 0) {
                trace.append(String.format(Locale.ROOT, " t%d:x%.1f,v%.3f,power%.2f", tick[0], s.getX() - startX, s.getDeltaMovement().horizontalDistance(), s.power()));
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(s.getX() - startX > 26.0, "down the tank:" + trace))
                .thenExecute(() -> from[0] = s.position())
                .thenIdle(10)
                .thenExecute(() -> {
                    double speed = s.position().subtract(from[0]).horizontalDistance() / 10.0;
                    helper.assertTrue(Math.abs(speed - TOP_SPEED) <= 0.05 * TOP_SPEED, "at its top speed " + TOP_SPEED + ": " + speed + trace);
                })
                .thenSucceed();
    }

    @GameTest(template = "tank", timeoutTicks = 400)
    public void fourRideAndBreatheAtDepthAndAFifthIsTurnedAway(GameTestHelper helper) {
        pool(helper, POOL);
        Submarine s = sub(helper, 24.5, 4, 12.5, 0.0f);
        standIn(helper, s);
        s.setScriptedDive(dive(0, 0, 0));
        List<Villager> crew = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Villager v = villager(helper, s.position());
            helper.assertTrue(v.startRiding(s, true), "aboard: " + (i + 2));
            crew.add(v);
        }
        Villager fifth = villager(helper, helper.absoluteVec(new Vec3(36.5, 2, 12.5)));
        helper.assertTrue(!fifth.startRiding(s, false), "a fifth finds no seat");
        helper.assertValueEqual(s.getPassengers().size(), 4, "four aboard");
        helper.startSequence()
                .thenIdle(300)
                .thenExecute(() -> {
                    for (Villager v : crew) {
                        helper.assertTrue(v.isEyeInFluid(FluidTags.WATER), "each rider's eyes are under water");
                        helper.assertTrue(v.getAirSupply() >= v.getMaxAirSupply() - 1, "and breathes: air " + v.getAirSupply());
                        helper.assertValueEqual(v.getHealth(), v.getMaxHealth(), "unhurt after fifteen seconds down");
                    }
                })
                .thenSucceed();
    }

    /**
     * The hold: a double chest's three rows hidden in the solid stern under the deck hatch. A click on
     * the hatch, sent as the client sends it from beside and above, opens it; a rider's inventory
     * key opens it.
     */
    @GameTest(template = "tank", timeoutTicks = 60)
    public void itsHoldOpensThroughTheDeckHatchAndFromASeat(GameTestHelper helper) {
        for (int x = 0; x < LENGTH; x++) {
            for (int z = 0; z < WIDTH; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        // Square on to the world, where the body's own box (a square under the sail) stops short of the hatch's after half.
        Submarine s = sub(helper, 24.5, 1, 12.5, 0.0f);
        VehicleProfile p = s.profile();
        ServerPlayer porter = player(helper, "porter", new Vec3(2.5, 1, 2.5));
        helper.runAtTickTime(10, () -> {
            Vec3 store = s.position().add(s.rotate(p.localBlocks(p.storage().orElseThrow().chests().get(0).at())));
            // The hatch over it, at its after end, where only the tail's hit box covers it.
            Vec3 aft = s.rotate(new Vec(0.0, 0.0, -0.30));
            Vec3 hatch = store.add(aft).add(0.0, 0.54, 0.0);
            Vec3 across = s.rotate(new Vec(1.0, 0.0, 0.0));
            Vec3 eye = hatch.add(across.scale(1.8)).add(0.0, 1.30, 0.0);
            porter.teleportTo(eye.x, eye.y - porter.getEyeHeight(), eye.z);
            Vec3 inside = hatch.add(hatch.subtract(eye).normalize().scale(0.3));
            Entity target = null;
            Vec3 hit = null;
            List<Entity> boxes = new ArrayList<>(List.of(s.getParts()));
            boxes.add(s);
            for (Entity e : boxes) {
                Optional<Vec3> clip = e.getBoundingBox().clip(porter.getEyePosition(), inside);
                if (clip.isPresent() && (hit == null || clip.get().distanceToSqr(porter.getEyePosition()) < hit.distanceToSqr(porter.getEyePosition()))) {
                    target = e;
                    hit = clip.get();
                }
            }
            helper.assertTrue(target != null, "a hit box covers the hatch");
            porter.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(target, false, InteractionHand.MAIN_HAND, hit.subtract(target.position())));
            helper.assertTrue(porter.containerMenu instanceof ChestMenu m && m.getRowCount() == 3, "the hatch opens three rows: " + porter.containerMenu);
            porter.closeContainer();
            // Aboard, the inventory key: the hold.
            helper.assertTrue(porter.startRiding(s, true), "aboard");
            s.openCustomInventoryScreen(porter);
            helper.assertTrue(porter.containerMenu instanceof ChestMenu m && m.getRowCount() == 3, "a rider's inventory key opens the hold: " + porter.containerMenu);
            logOff(porter);
            helper.succeed();
        });
    }

    @GameTest(template = "tank", timeoutTicks = 120)
    public void aTorpedoLeavesEachChinTubeAheadOfTheBow(GameTestHelper helper) {
        pool(helper, POOL);
        Submarine s = sub(helper, 8.5, 5, 12.5, -90.0f);   // facing +x
        s.fitOut().setItem(s.weaponSlot(0), new ItemStack(SubmersiblesContent.TORPEDO_TUBE.get()));
        s.fitOut().setItem(s.weaponSlot(1), new ItemStack(SubmersiblesContent.TORPEDO_TUBE.get()));
        s.getItemStacks().set(0, new ItemStack(SubmersiblesContent.TORPEDO_ITEM.get(), 4));
        ServerPlayer pilot = player(helper, "pilot", new Vec3(8.5, 5, 12.5));
        helper.assertTrue(pilot.startRiding(s, true), "at the controls");
        AABB tank = new AABB(helper.absoluteVec(Vec3.ZERO), helper.absoluteVec(new Vec3(LENGTH, HEIGHT, WIDTH)));
        Vec3 ahead = s.rotate(new Vec(0.0, 0.0, 1.0));
        Vec3 left = s.rotate(new Vec(1.0, 0.0, 0.0));
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    s.fire(pilot);
                    s.fire(pilot);
                    List<Torpedo> run = helper.getLevel().getEntitiesOfClass(Torpedo.class, tank);
                    helper.assertValueEqual(run.size(), 2, "both tubes fired");
                    double[] side = new double[2];
                    for (int i = 0; i < 2; i++) {
                        Vec3 d = run.get(i).position().subtract(s.position());
                        helper.assertTrue(d.dot(ahead) > 2.0, "from the chin, ahead of the bow: " + d.dot(ahead));
                        helper.assertTrue(d.y > 0.0 && d.y < 0.5, "low, at the tubes: " + d.y);
                        side[i] = d.dot(left);
                    }
                    helper.assertTrue(Math.abs(Math.abs(side[0] - side[1]) - 0.60) < 0.15, "one from each tube: " + side[0] + ", " + side[1]);
                })
                .thenIdle(15)
                .thenExecute(() -> {
                    for (Torpedo t : helper.getLevel().getEntitiesOfClass(Torpedo.class, tank)) {
                        helper.assertTrue(t.position().subtract(s.position()).dot(ahead) > 12.0, "running on ahead");
                    }
                    helper.assertValueEqual(s.condition(), Condition.MAX, "its own torpedoes never touch it");
                    logOff(pilot);
                })
                .thenSucceed();
    }
}
