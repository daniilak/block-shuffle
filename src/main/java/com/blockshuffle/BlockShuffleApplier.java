package com.blockshuffle;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockShuffleApplier {
	private static final int SHAPED_BLOCK_CHANCE = 32;
	private static final int BLOCK_ENTITY_CHANCE = 2048;

	private static List<Block> TERRAIN_POOL = List.of();
	private static List<Block> SHAPED_POOL = List.of();
	private static List<Block> BLOCK_ENTITY_POOL = List.of();
	private static final Set<Block> RUNTIME_QUARANTINE = ConcurrentHashMap.newKeySet();
	/** Угол отдельного куска биома. Одинаковый биом в разных местах получает разные углы. */
	private static final Long2LongOpenHashMap REGION_CORNERS = new Long2LongOpenHashMap();
	private static final LongArrayList REGION_PATH = new LongArrayList();
	private static boolean poolsReady;

	public record PoolStats(
			int total,
			int terrain,
			int shaped,
			int blockEntities,
			boolean craftingTable,
			boolean beeNest
	) {
	}

	private BlockShuffleApplier() {
	}

	public static void ensurePools() {
		rebuildPools(false);
	}

	public static void rebuildPools() {
		rebuildPools(true);
	}

	private static synchronized void rebuildPools(boolean force) {
		if (poolsReady && !force) {
			return;
		}

		List<Block> terrain = new ArrayList<>();
		List<Block> shaped = new ArrayList<>();
		List<Block> blockEntities = new ArrayList<>();

		for (Block block : BuiltInRegistries.BLOCK) {
			if (!isShuffleCandidate(block)) {
				continue;
			}

			if (block instanceof EntityBlock) {
				blockEntities.add(block);
			} else if (block instanceof FallingBlock || !block.defaultBlockState().isCollisionShapeFullBlock(
					EmptyBlockGetter.INSTANCE,
					BlockPos.ZERO
			)) {
				shaped.add(block);
			} else {
				terrain.add(block);
			}
		}

		if (terrain.isEmpty()) {
			terrain.add(Blocks.STONE);
		}

		TERRAIN_POOL = List.copyOf(terrain);
		SHAPED_POOL = List.copyOf(shaped);
		BLOCK_ENTITY_POOL = List.copyOf(blockEntities);
		poolsReady = true;

		PoolStats stats = poolStats();
		BlockShuffleMod.LOGGER.info(
				"Block Shuffle complete pool: total={}, terrain={}, shaped={}, blockEntities={}, craftingTable={}, beeNest={}",
				stats.total(),
				stats.terrain(),
				stats.shaped(),
				stats.blockEntities(),
				stats.craftingTable(),
				stats.beeNest()
		);
	}

	public static PoolStats poolStats() {
		ensurePools();
		return new PoolStats(
				TERRAIN_POOL.size() + SHAPED_POOL.size() + BLOCK_ENTITY_POOL.size(),
				TERRAIN_POOL.size(),
				SHAPED_POOL.size(),
				BLOCK_ENTITY_POOL.size(),
				contains(Blocks.CRAFTING_TABLE),
				contains(Blocks.BEE_NEST)
		);
	}

	public static RandomSource chunkRandom(ServerLevel level, long chunkKey) {
		return RandomSource.create(
				level.getSeed()
						^ dimensionSalt(level)
						^ chunkKey * 341873128712L
						^ 0x51C1D5L
		);
	}

	public static int applyRadius(ServerLevel level, BlockPos center, int radius) {
		ensurePools();
		BlockShuffleState settings = BlockShuffleState.get(level);
		BlockPos min = center.offset(-radius, -radius, -radius);
		BlockPos max = center.offset(radius, radius, radius);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			BlockPos immutable = pos.immutable();
			if (level.getBlockState(immutable).hasBlockEntity()) {
				level.getBlockEntity(immutable);
			}
		}

		Set<LevelChunk> dirty = new HashSet<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int replaced = 0;
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			cursor.set(pos);
			LevelChunk chunk = level.getChunkAt(cursor);
			if (writeBlock(level, chunk, cursor, settings, null)) {
				replaced++;
				dirty.add(chunk);
			}
		}
		for (LevelChunk chunk : dirty) {
			finishChunk(level, chunk);
		}
		return replaced;
	}

	public static int applyChunk(ServerLevel level, LevelChunk chunk) {
		ensurePools();
		promotePendingBlockEntities(chunk);
		int scale = BlockShuffleState.get(level).getScale();
		int replaced = switch (scale) {
			case BlockShuffleState.SCALE_CHUNK -> fillUniform(level, chunk, blockForChunk(level, chunk.getPos().toLong()));
			case BlockShuffleState.SCALE_BIOME -> fillBiomes(level, chunk);
			default -> fillScaled(level, chunk, scale);
		};
		if (replaced > 0) {
			finishChunk(level, chunk);
		}
		return replaced;
	}

	private static void promotePendingBlockEntities(LevelChunk chunk) {
		ChunkPos chunkPos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSection() << 4;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section.hasOnlyAir() || !section.maybeHas(BlockState::hasBlockEntity)) {
				continue;
			}

			int originY = minSectionY + (sectionIndex << 4);
			for (int lx = 0; lx < 16; lx++) {
				for (int ly = 0; ly < 16; ly++) {
					for (int lz = 0; lz < 16; lz++) {
						if (!section.getBlockState(lx, ly, lz).hasBlockEntity()) {
							continue;
						}

						chunk.getBlockEntity(new BlockPos(
								chunkPos.getMinBlockX() + lx,
								originY + ly,
								chunkPos.getMinBlockZ() + lz
						));
					}
				}
			}
		}
	}

	private static int fillUniform(ServerLevel level, LevelChunk chunk, Block block) {
		return fillPositions(chunk, (section, lx, ly, lz, pos) -> {
			BlockState old = section.getBlockState(lx, ly, lz);
			if (shouldLeaveAlone(old) || old.getBlock() == block) {
				return false;
			}
			return place(level, chunk, section, lx, ly, lz, pos, block);
		});
	}

	private static int fillBiomes(ServerLevel level, LevelChunk chunk) {
		Map<Long, Block> biomes = new HashMap<>();
		ChunkPos chunkPos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSection() << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int replaced = 0;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section.hasOnlyAir()) {
				continue;
			}

			int originY = minSectionY + (sectionIndex << 4);
			for (int quartZ = 0; quartZ < 4; quartZ++) {
				for (int quartY = 0; quartY < 4; quartY++) {
					for (int quartX = 0; quartX < 4; quartX++) {
						int worldQuartX = (chunkPos.getMinBlockX() >> 2) + quartX;
						int worldQuartY = (originY >> 2) + quartY;
						int worldQuartZ = (chunkPos.getMinBlockZ() >> 2) + quartZ;
						Block block = blockForBiome(
								level,
								section.getNoiseBiome(quartX, quartY, quartZ),
								worldQuartX,
								worldQuartY,
								worldQuartZ,
								biomes
						);
						int originX = quartX << 2;
						int originLocalY = quartY << 2;
						int originZ = quartZ << 2;
						for (int dz = 0; dz < 4; dz++) {
							for (int dy = 0; dy < 4; dy++) {
								for (int dx = 0; dx < 4; dx++) {
									int lx = originX + dx;
									int ly = originLocalY + dy;
									int lz = originZ + dz;
									BlockState old = section.getBlockState(lx, ly, lz);
									if (shouldLeaveAlone(old) || old.getBlock() == block) {
										continue;
									}
									pos.set(chunkPos.getMinBlockX() + lx, originY + ly, chunkPos.getMinBlockZ() + lz);
									if (place(level, chunk, section, lx, ly, lz, pos, block)) {
										replaced++;
									}
								}
							}
						}
					}
				}
			}
		}
		return replaced;
	}

	private static int fillScaled(ServerLevel level, LevelChunk chunk, int scale) {
		int safeScale = BlockShuffleState.normalizeScale(scale);
		long salt = level.getSeed() ^ dimensionSalt(level) ^ (long) safeScale * 0x9E3779B97F4A7C15L;
		boolean completePool = safeScale == 1;
		ChunkPos chunkPos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSection() << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int cellX = Integer.MIN_VALUE;
		int cellY = Integer.MIN_VALUE;
		int cellZ = Integer.MIN_VALUE;
		RandomSource random = null;
		Block picked = Blocks.STONE;
		int replaced = 0;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section.hasOnlyAir()) {
				continue;
			}

			int originY = minSectionY + (sectionIndex << 4);
			for (int lx = 0; lx < 16; lx++) {
				for (int ly = 0; ly < 16; ly++) {
					for (int lz = 0; lz < 16; lz++) {
						BlockState old = section.getBlockState(lx, ly, lz);
						if (shouldLeaveAlone(old)) {
							continue;
						}

						int worldX = chunkPos.getMinBlockX() + lx;
						int worldY = originY + ly;
						int worldZ = chunkPos.getMinBlockZ() + lz;
						int nextCellX = Math.floorDiv(worldX, safeScale);
						int nextCellY = Math.floorDiv(worldY, safeScale);
						int nextCellZ = Math.floorDiv(worldZ, safeScale);
						if (nextCellX != cellX || nextCellY != cellY || nextCellZ != cellZ) {
							cellX = nextCellX;
							cellY = nextCellY;
							cellZ = nextCellZ;
							random = RandomSource.create(cellSeed(salt, cellX, cellY, cellZ));
							picked = pickCandidate(random, completePool);
						}

						pos.set(worldX, worldY, worldZ);
						Block block = picked;
						if (!block.defaultBlockState().canSurvive(level, pos)) {
							block = TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
						}
						if (old.getBlock() != block && place(level, chunk, section, lx, ly, lz, pos, block)) {
							replaced++;
						}
					}
				}
			}
		}
		return replaced;
	}

	private static int fillPositions(LevelChunk chunk, SectionPlacer placer) {
		ChunkPos chunkPos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSection() << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int replaced = 0;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section.hasOnlyAir()) {
				continue;
			}

			int originY = minSectionY + (sectionIndex << 4);
			for (int lx = 0; lx < 16; lx++) {
				for (int ly = 0; ly < 16; ly++) {
					for (int lz = 0; lz < 16; lz++) {
						pos.set(chunkPos.getMinBlockX() + lx, originY + ly, chunkPos.getMinBlockZ() + lz);
						if (placer.place(section, lx, ly, lz, pos)) {
							replaced++;
						}
					}
				}
			}
		}
		return replaced;
	}

	@FunctionalInterface
	private interface SectionPlacer {
		boolean place(LevelChunkSection section, int localX, int localY, int localZ, BlockPos.MutableBlockPos pos);
	}

	private static boolean writeBlock(
			ServerLevel level,
			LevelChunk chunk,
			BlockPos.MutableBlockPos pos,
			BlockShuffleState settings,
			Block fixed
	) {
		if (shouldLeaveAlone(chunk.getBlockState(pos))) {
			return false;
		}
		Block replacement = fixed != null ? fixed : pickForCell(level, pos, settings.getScale());
		int y = pos.getY();
		return place(
				level,
				chunk,
				chunk.getSection(chunk.getSectionIndex(y)),
				pos.getX() & 15,
				y & 15,
				pos.getZ() & 15,
				pos,
				replacement
		);
	}

	private static boolean place(
			ServerLevel level,
			LevelChunk chunk,
			LevelChunkSection section,
			int localX,
			int localY,
			int localZ,
			BlockPos pos,
			Block replacement
	) {
		if (RUNTIME_QUARANTINE.contains(replacement)) {
			replacement = Blocks.STONE;
		}

		BlockState placed = replacement.defaultBlockState();
		BlockState old = section.getBlockState(localX, localY, localZ);
		if (old == placed) {
			return false;
		}
		if (old.hasBlockEntity()) {
			chunk.removeBlockEntity(pos);
		}

		try {
			section.setBlockState(localX, localY, localZ, placed, false);
			return true;
		} catch (RuntimeException error) {
			if (RUNTIME_QUARANTINE.add(replacement)) {
				BlockShuffleMod.LOGGER.error(
						"Block Shuffle quarantined unsafe replacement {} at {} in {}",
						BuiltInRegistries.BLOCK.getKey(replacement),
						pos,
						level.dimension().location(),
						error
				);
			}

			try {
				section.setBlockState(localX, localY, localZ, Blocks.STONE.defaultBlockState(), false);
				return true;
			} catch (RuntimeException fallbackError) {
				error.addSuppressed(fallbackError);
				throw error;
			}
		}
	}

	private static void finishChunk(ServerLevel level, LevelChunk chunk) {
		EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
		for (var entry : chunk.getHeightmaps()) {
			types.add(entry.getKey());
		}
		if (!types.isEmpty()) {
			Heightmap.primeHeightmaps(chunk, types);
		}
		chunk.setUnsaved(true);

		var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
		if (players.isEmpty()) {
			chunk.setLightCorrect(false);
			return;
		}

		level.getChunkSource().getLightEngine().lightChunk(chunk, false);
		ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
				chunk,
				level.getChunkSource().getLightEngine(),
				null,
				null
		);
		for (ServerPlayer player : players) {
			player.connection.send(packet);
		}
	}

	private static Block blockForChunk(ServerLevel level, long chunkKey) {
		RandomSource random = chunkRandom(level, chunkKey);
		return TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
	}

	private static long cellSeed(long salt, int cellX, int cellY, int cellZ) {
		return salt
				^ (long) cellX * 73428767L
				^ (long) cellY * 19349663L
				^ (long) cellZ * 83492791L;
	}

	/**
	 * Масштаб 1 использует полный пул. Блоки сложной формы встречаются реже,
	 * а блоки-сущности — очень редко, чтобы мир оставался пригодным для игры.
	 * Крупные масштабы используют только обычные блоки: куб из тысяч сундуков
	 * или ульев создал бы тысячи block entity и повредил производительности.
	 */
	private static Block pickForCell(ServerLevel level, BlockPos pos, int scale) {
		ensurePools();
		int safeScale = BlockShuffleState.normalizeScale(scale);
		if (safeScale == BlockShuffleState.SCALE_CHUNK) {
			RandomSource random = chunkRandom(level, ChunkPos.asLong(pos));
			return TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
		}
		if (safeScale == BlockShuffleState.SCALE_BIOME) {
			return blockForBiome(level, level.getBiome(pos), pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2, null);
		}

		int cellX = Math.floorDiv(pos.getX(), safeScale);
		int cellY = Math.floorDiv(pos.getY(), safeScale);
		int cellZ = Math.floorDiv(pos.getZ(), safeScale);
		long seed = level.getSeed()
				^ dimensionSalt(level)
				^ (long) cellX * 73428767L
				^ (long) cellY * 19349663L
				^ (long) cellZ * 83492791L
				^ (long) safeScale * 0x9E3779B97F4A7C15L;
		RandomSource random = RandomSource.create(seed);

		Block selected = pickCandidate(random, safeScale == 1);
		if (!selected.defaultBlockState().canSurvive(level, pos)) {
			selected = TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
		}
		return selected;
	}

	private static Block blockForBiome(
			ServerLevel level,
			Holder<Biome> biome,
			int quartX,
			int quartY,
			int quartZ,
			Map<Long, Block> cache
	) {
		ensurePools();
		ResourceLocation id = biomeId(biome);
		long corner = regionCorner(level, quartX, quartY, quartZ, id);
		if (cache != null) {
			Block cached = cache.get(corner);
			if (cached != null) {
				return cached;
			}
		}

		long seed = level.getSeed()
				^ dimensionSalt(level)
				^ id.hashCode() * 0x9E3779B97F4A7C15L
				^ corner
				^ 0xB10E5L;
		RandomSource random = RandomSource.create(seed);
		Block selected = TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
		if (cache != null) {
			cache.put(corner, selected);
		}
		return selected;
	}

	/**
	 * Отдельные куски одного биома расходятся: из точки идём на запад, юг и вниз,
	 * пока биом не кончится. Угол этого пути и есть номер куска.
	 */
	private static long regionCorner(ServerLevel level, int x, int y, int z, ResourceLocation biome) {
		long cached = cachedCorner(packQuart(x, y, z));
		if (cached != Long.MIN_VALUE) {
			return cached;
		}

		REGION_PATH.clear();
		int guard = 0;
		while (guard++ < 100_000) {
			long key = packQuart(x, y, z);
			cached = cachedCorner(key);
			if (cached != Long.MIN_VALUE) {
				rememberPath(cached);
				return cached;
			}
			REGION_PATH.add(key);
			if (sameBiome(level, x - 1, y, z, biome)) {
				x--;
				continue;
			}
			if (sameBiome(level, x, y, z - 1, biome)) {
				z--;
				continue;
			}
			if (sameBiome(level, x, y - 1, z, biome)) {
				y--;
				continue;
			}
			break;
		}

		long corner = packQuart(x, y, z);
		rememberPath(corner);
		return corner;
	}

	private static long cachedCorner(long quart) {
		return REGION_CORNERS.containsKey(quart) ? REGION_CORNERS.get(quart) : Long.MIN_VALUE;
	}

	private static void rememberPath(long corner) {
		for (int i = 0; i < REGION_PATH.size(); i++) {
			REGION_CORNERS.put(REGION_PATH.getLong(i), corner);
		}
	}

	private static boolean sameBiome(ServerLevel level, int quartX, int quartY, int quartZ, ResourceLocation biome) {
		return biome.equals(biomeId(level.getUncachedNoiseBiome(quartX, quartY, quartZ)));
	}

	private static ResourceLocation biomeId(Holder<Biome> biome) {
		return biome.unwrapKey().map(ResourceKey::location).orElse(UNKNOWN_BIOME);
	}

	private static long packQuart(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
	}

	private static final ResourceLocation UNKNOWN_BIOME = ResourceLocation.withDefaultNamespace("unknown");

	private static Block pickCandidate(RandomSource random, boolean completePool) {
		if (completePool && !BLOCK_ENTITY_POOL.isEmpty() && random.nextInt(BLOCK_ENTITY_CHANCE) == 0) {
			return BLOCK_ENTITY_POOL.get(random.nextInt(BLOCK_ENTITY_POOL.size()));
		}
		if (completePool && !SHAPED_POOL.isEmpty() && random.nextInt(SHAPED_BLOCK_CHANCE) == 0) {
			return SHAPED_POOL.get(random.nextInt(SHAPED_POOL.size()));
		}
		return TERRAIN_POOL.get(random.nextInt(TERRAIN_POOL.size()));
	}

	private static boolean shouldLeaveAlone(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return true;
		}
		return isTechnicalBlock(state.getBlock());
	}

	private static boolean isShuffleCandidate(Block block) {
		BlockState state = block.defaultBlockState();
		return block.asItem() != Items.AIR
				&& !state.isAir()
				&& state.getFluidState().isEmpty()
				&& !isTechnicalBlock(block)
				&& !hasUnsafePlacementSideEffects(block);
	}

	private static boolean hasUnsafePlacementSideEffects(Block block) {
		return block instanceof CarvedPumpkinBlock || block instanceof WitherSkullBlock;
	}

	private static boolean isTechnicalBlock(Block block) {
		return block == Blocks.BEDROCK
				|| block == Blocks.BARRIER
				|| block == Blocks.COMMAND_BLOCK
				|| block == Blocks.CHAIN_COMMAND_BLOCK
				|| block == Blocks.REPEATING_COMMAND_BLOCK
				|| block == Blocks.NETHER_PORTAL
				|| block == Blocks.END_PORTAL
				|| block == Blocks.END_PORTAL_FRAME
				|| block == Blocks.END_GATEWAY
				|| block == Blocks.MOVING_PISTON
				|| block == Blocks.PISTON_HEAD
				|| block == Blocks.STRUCTURE_VOID
				|| block == Blocks.STRUCTURE_BLOCK
				|| block == Blocks.JIGSAW
				|| block == Blocks.LIGHT;
	}

	private static boolean contains(Block block) {
		return TERRAIN_POOL.contains(block)
				|| SHAPED_POOL.contains(block)
				|| BLOCK_ENTITY_POOL.contains(block);
	}

	private static long dimensionSalt(ServerLevel level) {
		return (long) level.dimension().location().hashCode() * 0xD6E8FEB86659FD93L;
	}
}
