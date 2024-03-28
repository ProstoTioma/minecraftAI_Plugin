package com.example;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import jdk.javadoc.doclet.Taglet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.server.ServerTickCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.LocateCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;


import javax.xml.stream.Location;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ExampleMod implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("Starting...");
        String host = "127.0.0.1";  // localhost
        int port = 12345;

        // Create a socket to connect to the Python script
        Socket socket = null;
        try {
            socket = new Socket(host, port);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Create an output stream to send data to the Python script
        OutputStream outputStream = null;
        try {
            outputStream = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        PrintWriter out = new PrintWriter(outputStream, true);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("info")
                    .executes(context -> {
                        PlayerEntity player = context.getSource().getPlayer();
                        int health = (int) player.getHealth();
                        int saturation = (int) player.getHungerManager().getFoodLevel();
                        System.out.println("Current hp level: %s, current food level: %s".formatted(health, saturation));
                        context.getSource().sendFeedback(() -> Text.literal("Current hp level: %s, current food level: %s".formatted(health, saturation)), false);
                        return 10;
                    }));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("inventory")
                    .executes(context -> {
                        PlayerEntity player = context.getSource().getPlayer();
                        List<ItemStack> inventoryItems = new ArrayList<>();

                        // Add each item in the player's inventory to the list
                        for (int i = 0; i < player.getInventory().size(); i++) {
                            ItemStack stack = player.getInventory().getStack(i);
                            if (!stack.isEmpty()) {
                                inventoryItems.add(stack);
                            }
                        }

                        // Convert the list of ItemStacks to a string representation
                        StringBuilder inventoryString = new StringBuilder();
                        inventoryString.append("Inventory Items:\n");
                        for (ItemStack stack : inventoryItems) {
                            inventoryString.append(stack.getCount()).append("x ").append(stack.getName().getString()).append("\n");
                        }

                        // Send the inventory items as a message to the player
                        context.getSource().sendFeedback(() -> Text.literal("%s".formatted(inventoryString)), false);

                        // Parse the command
                        ParseResults<ServerCommandSource> parseResults = dispatcher.parse("info", context.getSource());
                        context.getSource().getServer().getCommandManager().execute(parseResults, "info");

                        return 1;
                    }));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("getNearBlocks")
                    .then(CommandManager.argument("distance", IntegerArgumentType.integer())
                            .executes(context -> {
                                int distance = IntegerArgumentType.getInteger(context, "distance");
                                ServerCommandSource source = context.getSource();
                                PlayerEntity player = source.getPlayer();
                                World world = source.getWorld();
                                BlockPos playerPos = player.getBlockPos();
                                ArrayList<BlockInfo> blocks = new ArrayList<>();

                                for (int dx = -distance; dx <= distance; dx++) {
                                    for (int dz = -distance; dz <= distance; dz++) {
                                        for (int dy = 0; dy <= distance; dy++) { // Check blocks above the player
                                            BlockPos currentPos = playerPos.add(dx, dy, dz);
                                            BlockState blockState = world.getBlockState(currentPos);
                                            Block block = blockState.getBlock();
                                            if (block != Blocks.AIR) { // Exclude air blocks
                                                LOGGER.info("Block at: " + currentPos + ", Type: " + block);
                                                source.sendFeedback(() -> Text.literal("Block at: %s, type: %s".formatted(currentPos, block)), false);
                                                blocks.add(new BlockInfo(block, currentPos));
                                            }
                                        }
                                    }
                                }
                                source.sendFeedback(() -> Text.literal("Amount of blocks found: %s".formatted(blocks.size())), false);
                                return 1;

                            })));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("send")
                    .executes(context -> {
                        // Capture console output
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        PrintStream ps = new PrintStream(baos);
                        PrintStream old = System.out;
                        System.setOut(ps);

                        // Execute the info command
                        ParseResults<ServerCommandSource> parseResults = dispatcher.parse("info", context.getSource());
                        try {
                            dispatcher.execute(parseResults);
                        } catch (CommandSyntaxException e) {
                            e.printStackTrace();
                        }

                        // Restore console output
                        System.out.flush();
                        System.setOut(old);

                        // Get captured console output
                        String infoOutput = baos.toString();

                        // Send the captured output to Python
                        out.println(infoOutput);
                        return 1;
                    }));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("start")
                    .executes(context -> {
                        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
                        executor.scheduleAtFixedRate(() -> {
                            try {
                                ParseResults<ServerCommandSource> parseResults2 = dispatcher.parse("send", context.getSource());
                                dispatcher.execute(parseResults2);
                            } catch (CommandSyntaxException e) {
                                e.printStackTrace();
                            }

                        }, 0, 3, TimeUnit.SECONDS);

                        return 1;
                    }));
        });


    }

    class BlockInfo {
        private Block block;
        private BlockPos position;

        public BlockInfo(Block block, BlockPos position) {
            this.block = block;
            this.position = position;
        }

        public Block getBlock() {
            return block;
        }

        public BlockPos getPosition() {
            return position;
        }
    }
}
