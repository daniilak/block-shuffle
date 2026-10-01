package com.blockshuffle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlockShuffleMod implements ModInitializer {
	public static final String MOD_ID = "blockshuffle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<ResourceKey<Level>, LongArrayFIFOQueue> APPLY = new HashMap<>();
	private static final Map<ResourceKey<Level>, LongOpenHashSet> APPLY_SEEN = new HashMap<>();
	private static final Map<ResourceKey<Level>, LongArrayFIFOQueue> SWEEP = new HashMap<>();
	private static final LongArrayFIFOQueue PREGEN = new LongArrayFIFOQueue();
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static ResourceKey<Level> pregenDimension;
	private static int pregenTotal;
	private static int pregenDone;
	private static int pregenAnnounceEvery = 200;
	private static int sweepTotal;
	private static int sweepDone;
	private static int sweepAnnounceEvery = 200;
	/** Сколько времени одного тика можно отдать замене, чтобы не замирала игра. */
	private static final long TICK_BUDGET_NANOS = 10_000_000L;
	private static final int MAX_CHUNKS_PER_TICK = 24;

	@Override
	public void onInitialize() {
		BlockShuffleCommand.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> BlockShuffleApplier.rebuildPools());
		ServerChunkEvents.CHUNK_LOAD.register(BlockShuffleMod::onChunkLoad);
		ServerTickEvents.END_WORLD_TICK.register(BlockShuffleMod::onWorldTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (BlockShuffleState.get(handler.player.serverLevel()).isAutoEnabled()) {
				return;
			}
			handler.player.sendSystemMessage(Component.literal(
					"Block Shuffle выключен. Запуск: /blockshuffle — все миры, или /blockshuffle overworld | nether | both"
			));
		});

		LOGGER.info("Block Shuffle loaded");
	}

	static int startPregen(ServerLevel level, int chunkRadius) {
		PREGEN.clear();
		pregenDimension = level.dimension();
		pregenDone = 0;
		ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
		for (int x = spawn.x - chunkRadius; x <= spawn.x + chunkRadius; x++) {
			for (int z = spawn.z - chunkRadius; z <= spawn.z + chunkRadius; z++) {
				PREGEN.enqueue(ChunkPos.asLong(x, z));
			}
		}
		pregenTotal = PREGEN.size();
		pregenAnnounceEvery = Math.max(50, pregenTotal / 20);
		return pregenTotal;
	}

	static void beginExistingSweep() {
		SWEEP.clear();
		APPLY.clear();
		APPLY_SEEN.clear();
		sweepDone = 0;
		sweepTotal = 0;
		sweepAnnounceEvery = 200;
	}

	static int queueExistingChunks(ServerLevel level) {
		BlockShuffleState.get(level).clearProcessed();
		level.save(null, true, false);
		LongArrayFIFOQueue queue = sweepQueue(level.dimension());
		int before = queue.size();
		listSavedChunks(level, queue);
		int added = queue.size() - before;
		sweepTotal += added;
		sweepAnnounceEvery = Math.max(50, sweepTotal / 20);
		return added;
	}

	private static LongArrayFIFOQueue sweepQueue(ResourceKey<Level> dimension) {
		return SWEEP.computeIfAbsent(dimension, ignored -> new LongArrayFIFOQueue());
	}

	private static boolean enqueue(ResourceKey<Level> dimension, long key) {
		LongOpenHashSet seen = APPLY_SEEN.computeIfAbsent(dimension, ignored -> new LongOpenHashSet());
		if (!seen.add(key)) {
			return false;
		}
		APPLY.computeIfAbsent(dimension, ignored -> new LongArrayFIFOQueue()).enqueue(key);
		return true;
	}

	private static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		BlockShuffleState state = BlockShuffleState.get(level);
		if (!state.isAutoEnabled()) {
			return;
		}

		long key = chunk.getPos().toLong();
		if (state.isProcessed(key)) {
			return;
		}

		enqueue(level.dimension(), key);
	}

	private static void onWorldTick(ServerLevel level) {
		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		tickPregen(level, deadline);
		int done = 0;
		while (hasTickTime(deadline, done)) {
			boolean swept = sweepOne(level);
			boolean applied = applyOne(level);
			if (!swept && !applied) {
				break;
			}
			done++;
		}
	}

	private static boolean hasTickTime(long deadline, int done) {
		return done < MAX_CHUNKS_PER_TICK && (done == 0 || System.nanoTime() < deadline);
	}

	private static void listSavedChunks(ServerLevel level, LongArrayFIFOQueue keys) {
		Path root = level.getServer().getWorldPath(LevelResource.ROOT);
		Path regionDir = DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
		if (!Files.isDirectory(regionDir)) {
			return;
		}

		try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.mca")) {
			for (Path file : files) {
				Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
				if (!matcher.matches()) {
					continue;
				}
				int regionX = Integer.parseInt(matcher.group(1));
				int regionZ = Integer.parseInt(matcher.group(2));
				readRegionHeader(file, regionX, regionZ, keys);
			}
		} catch (IOException exception) {
			LOGGER.error("Block Shuffle could not list chunks in {}", regionDir, exception);
		}
	}

	private static void readRegionHeader(Path file, int regionX, int regionZ, LongArrayFIFOQueue keys) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
			if (channel.read(header) < 4096) {
				return;
			}
			header.flip();
			for (int localZ = 0; localZ < 32; localZ++) {
				for (int localX = 0; localX < 32; localX++) {
					if (header.getInt() == 0) {
						continue;
					}
					keys.enqueue(ChunkPos.asLong(regionX * 32 + localX, regionZ * 32 + localZ));
				}
			}
		}
	}

	private static void tickPregen(ServerLevel level, long deadline) {
		if (PREGEN.isEmpty() || pregenDimension == null || !pregenDimension.equals(level.dimension())) {
			return;
		}

		int done = 0;
		while (!PREGEN.isEmpty() && hasTickTime(deadline, done)) {
			long key = PREGEN.dequeueLong();
			level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
			pregenDone++;
			done++;
			if (pregenDone == pregenTotal || pregenDone % pregenAnnounceEvery == 0) {
				int percent = pregenTotal == 0 ? 100 : (int) (pregenDone * 100L / pregenTotal);
				Component msg = Component.literal("Block Shuffle преген: " + pregenDone + "/" + pregenTotal + " (" + percent + "%)");
				for (ServerPlayer player : level.players()) {
					player.sendSystemMessage(msg);
				}
			}
		}
	}

	private static boolean sweepOne(ServerLevel level) {
		LongArrayFIFOQueue queue = SWEEP.get(level.dimension());
		if (queue == null || queue.isEmpty()) {
			return false;
		}

		long key = queue.dequeueLong();
		int x = ChunkPos.getX(key);
		int z = ChunkPos.getZ(key);
		level.getChunk(x, z);
		enqueue(level.dimension(), key);
		sweepDone++;
		if (sweepDone == sweepTotal || sweepDone % sweepAnnounceEvery == 0) {
			int percent = sweepTotal == 0 ? 100 : (int) (sweepDone * 100L / sweepTotal);
			Component msg = Component.literal(
					"Block Shuffle замена сохранённых чанков: " + sweepDone + "/" + sweepTotal + " (" + percent + "%)"
			);
			for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
				player.sendSystemMessage(msg);
			}
		}
		return true;
	}

	private static boolean applyOne(ServerLevel level) {
		LongArrayFIFOQueue queue = APPLY.get(level.dimension());
		if (queue == null || queue.isEmpty()) {
			return false;
		}

		long key = queue.dequeueLong();
		LongOpenHashSet seen = APPLY_SEEN.get(level.dimension());
		if (seen != null) {
			seen.remove(key);
		}
		if (!BlockShuffleState.get(level).isAutoEnabled()) {
			return true;
		}

		int x = ChunkPos.getX(key);
		int z = ChunkPos.getZ(key);
		if (!level.hasChunk(x, z)) {
			return true;
		}

		LevelChunk chunk = level.getChunk(x, z);
		BlockShuffleApplier.applyChunk(level, chunk);
		BlockShuffleState.get(level).markProcessed(key);
		return true;
	}
}
