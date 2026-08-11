package com.yljos;

import com.mojang.brigadier.arguments.StringArgumentType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public class TpaMod implements ModInitializer {
    public static final String MOD_ID = "tpa-mod";

    // Record to store home dimensions and coordinates
    public record HomeData(RegistryKey<World> dimension, Vec3d pos, float yaw, float pitch) {}

    // Persistent state class to handle NBT saving/loading
    public static class HomeState extends PersistentState {
        public final Map<UUID, HomeData> homes = new HashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
            NbtCompound homesNbt = new NbtCompound();
            homes.forEach((uuid, data) -> {
                NbtCompound homeTag = new NbtCompound();
                homeTag.putString("dim", data.dimension().getValue().toString());
                homeTag.putDouble("x", data.pos().x);
                homeTag.putDouble("y", data.pos().y);
                homeTag.putDouble("z", data.pos().z);
                homeTag.putFloat("yaw", data.yaw());
                homeTag.putFloat("pitch", data.pitch());
                homesNbt.put(uuid.toString(), homeTag);
            });
            nbt.put("homes", homesNbt);
            return nbt;
        }

        public static HomeState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
            HomeState state = new HomeState();
            NbtCompound homesNbt = nbt.getCompound("homes");
            for (String key : homesNbt.getKeys()) {
                NbtCompound homeTag = homesNbt.getCompound(key);
                RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(homeTag.getString("dim")));
                Vec3d pos = new Vec3d(homeTag.getDouble("x"), homeTag.getDouble("y"), homeTag.getDouble("z"));
                float yaw = homeTag.getFloat("yaw");
                float pitch = homeTag.getFloat("pitch");
                state.homes.put(UUID.fromString(key), new HomeData(dim, pos, yaw, pitch));
            }
            return state;
        }

        public static HomeState getServerState(MinecraftServer server) {
            PersistentStateManager persistentStateManager = server.getWorld(World.OVERWORLD).getPersistentStateManager();
            Type<HomeState> type = new Type<>(HomeState::new, HomeState::fromNbt, null);
            return persistentStateManager.getOrCreate(type, MOD_ID);
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /tpa <player>
            dispatcher.register(CommandManager.literal("tpa")
                    // Require permission, default fallback to level 0 (everyone) if no LuckPerms
                    .requires(Permissions.require("tpa.command.tpa", 0))
                    // Use StringArgumentType to avoid target selectors
                    .then(CommandManager.argument("target", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                // Filter out the command source
                                String sourceName = context.getSource().getName();
                                Stream<String> players = Arrays.stream(context.getSource().getServer().getPlayerManager().getPlayerNames())
                                        .filter(name -> !name.equals(sourceName));

                                return CommandSource.suggestMatching(players, builder);
                            })
                            .executes(context -> {
                                ServerPlayerEntity source = context.getSource().getPlayerOrThrow();
                                String targetName = StringArgumentType.getString(context, "target");
                                ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(targetName);

                                teleportWithEffects(source, target.getServerWorld(), target.getPos(), target.getYaw(), target.getPitch());
                                return 1;
                            })));

            // /sethome
            dispatcher.register(CommandManager.literal("sethome")
                    // Require permission, default fallback to level 0 (everyone) if no LuckPerms
                    .requires(Permissions.require("tpa.command.sethome", 0))
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        HomeState state = HomeState.getServerState(player.getServer());
                        
                        state.homes.put(player.getUuid(), new HomeData(
                                player.getServerWorld().getRegistryKey(),
                                player.getPos(),
                                player.getYaw(),
                                player.getPitch()
                        ));
                        // Mark dirty to save to NBT
                        state.markDirty();
                        
                        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§aHome set.")));
                        return 1;
                    }));

            // /home
            dispatcher.register(CommandManager.literal("home")
                    // Require permission, default fallback to level 0 (everyone) if no LuckPerms
                    .requires(Permissions.require("tpa.command.home", 0))
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        HomeState state = HomeState.getServerState(player.getServer());
                        HomeData home = state.homes.get(player.getUuid());

                        if (home == null) {
                            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§cNo home.")));
                            return 0;
                        }

                        ServerWorld world = player.getServer().getWorld(home.dimension());
                        if (world != null) {
                            teleportWithEffects(player, world, home.pos(), home.yaw(), home.pitch());
                        }
                        return 1;
                    }));
        });
    }

    // Handles cross-dimension teleportation and visual/audio effects
    private static void teleportWithEffects(ServerPlayerEntity player, ServerWorld targetWorld, Vec3d pos, float yaw, float pitch) {
        // Play effect at the departure location
        playEndermanEffect(player.getServerWorld(), player.getPos());

        // Teleport player
        player.teleport(targetWorld, pos.x, pos.y, pos.z, Set.of(), yaw, pitch);

        // Play effect at the arrival location
        playEndermanEffect(targetWorld, pos);
    }

    // Spawns Enderman portal particles and plays the teleport sound
    private static void playEndermanEffect(ServerWorld world, Vec3d pos) {
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0F, 1.0F);
        world.spawnParticles(ParticleTypes.PORTAL, pos.x, pos.y + 1.0, pos.z, 32, 0.5, 1.0, 0.5, 0.1);
    }
}