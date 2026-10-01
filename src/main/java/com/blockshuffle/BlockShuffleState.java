package com.blockshuffle;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class BlockShuffleState extends SavedData {
	/** Специальный масштаб: весь чанк — один случайный блок. */
	public static final int SCALE_CHUNK = 0;
	/** Специальный масштаб: один биом — один случайный блок. Граница совпадает с границей биома. */
	public static final int SCALE_BIOME = -1;

	private boolean autoEnabled;
	/** Замена запущена командой. Старые миры без этого флага больше не стартуют сами. */
	private boolean commanded;
	/** 1, 2, 4, 8, 16, {@link #SCALE_CHUNK} или {@link #SCALE_BIOME}. */
	private int scale = 1;
	private final LongSet processedChunks = new LongOpenHashSet();

	public static BlockShuffleState get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(
				new SavedData.Factory<>(BlockShuffleState::new, BlockShuffleState::load, null),
				"blockshuffle"
		);
	}

	public static BlockShuffleState load(CompoundTag tag, HolderLookup.Provider registries) {
		BlockShuffleState state = new BlockShuffleState();
		state.commanded = tag.getBoolean("Commanded");
		if (state.commanded && tag.contains("Auto")) {
			state.autoEnabled = tag.getBoolean("Auto");
		}
		if (tag.contains("Scale")) {
			state.scale = normalizeScale(tag.getInt("Scale"));
		}
		for (long key : tag.getLongArray("Chunks")) {
			state.processedChunks.add(key);
		}
		return state;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("Commanded", commanded);
		tag.putBoolean("Auto", autoEnabled);
		tag.putInt("Scale", scale);
		tag.putLongArray("Chunks", processedChunks.toLongArray());
		return tag;
	}

	public static int normalizeScale(int scale) {
		return switch (scale) {
			case SCALE_BIOME, SCALE_CHUNK, 1, 2, 4, 8, 16 -> scale;
			default -> 1;
		};
	}

	public static String formatScale(int scale) {
		int safe = normalizeScale(scale);
		if (safe == SCALE_CHUNK) {
			return "chunk (весь чанк = 1 блок)";
		}
		if (safe == SCALE_BIOME) {
			return "biome (один биом = 1 блок)";
		}
		return safe + "×" + safe + "×" + safe;
	}

	public boolean isAutoEnabled() {
		return autoEnabled;
	}

	public void setAutoEnabled(boolean autoEnabled) {
		this.autoEnabled = autoEnabled;
		this.commanded = true;
		setDirty();
	}

	public int getScale() {
		return scale;
	}

	public void setScale(int scale) {
		this.scale = normalizeScale(scale);
		setDirty();
	}

	public boolean isProcessed(long chunkKey) {
		return processedChunks.contains(chunkKey);
	}

	public void markProcessed(long chunkKey) {
		if (processedChunks.add(chunkKey)) {
			setDirty();
		}
	}

	public void clearProcessed() {
		if (processedChunks.isEmpty()) {
			return;
		}
		processedChunks.clear();
		setDirty();
	}
}
