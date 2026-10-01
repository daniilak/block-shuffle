package com.blockshuffle;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlockShuffleCommand {
	private static final int MAX_AROUND = 32;
	private static final int MAX_BORDER_CHUNKS = 512;
	private static final List<String> SCALE_OPTIONS = List.of("1", "2", "4", "8", "16", "chunk", "biome");
	private static final List<String> SCOPE_OPTIONS = List.of("all", "overworld", "nether", "both");
	private static final List<ResourceKey<Level>> VANILLA_DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);
	private static final SuggestionProvider<CommandSourceStack> SCALE_SUGGESTIONS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(SCALE_OPTIONS, builder);
	private static final SuggestionProvider<CommandSourceStack> SCOPE_SUGGESTIONS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(SCOPE_OPTIONS, builder);

	private enum WorldScope {
		ALL,
		OVERWORLD,
		NETHER,
		BOTH;

		boolean includes(ResourceKey<Level> dimension) {
			boolean overworld = Level.OVERWORLD.equals(dimension);
			boolean nether = Level.NETHER.equals(dimension);
			return switch (this) {
				case ALL -> overworld || nether || Level.END.equals(dimension);
				case OVERWORLD -> overworld;
				case NETHER -> nether;
				case BOTH -> overworld || nether;
			};
		}

		String label() {
			return switch (this) {
				case ALL -> "все миры (Верхний, Нижний и Энд)";
				case OVERWORLD -> "только Верхний мир";
				case NETHER -> "только Нижний мир";
				case BOTH -> "Нижний и Верхний миры";
			};
		}
	}

	private BlockShuffleCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("blockshuffle")
					.requires(source -> source.hasPermission(2))
					.executes(ctx -> runScope(ctx.getSource(), WorldScope.ALL))
					.then(Commands.argument("scope", StringArgumentType.greedyString())
							.suggests(SCOPE_SUGGESTIONS)
							.executes(ctx -> runScopeArg(
									ctx.getSource(),
									StringArgumentType.getString(ctx, "scope")
							)))
					.then(Commands.literal("world")
							.executes(ctx -> runScope(ctx.getSource(), WorldScope.ALL))
							.then(Commands.argument("scope", StringArgumentType.greedyString())
									.suggests(SCOPE_SUGGESTIONS)
									.executes(ctx -> runScopeArg(
											ctx.getSource(),
											StringArgumentType.getString(ctx, "scope")
									))))
					.then(Commands.literal("around")
							.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_AROUND))
									.executes(ctx -> runRadius(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "radius")
									))))
					.then(Commands.literal("border")
							.then(Commands.argument("chunks", IntegerArgumentType.integer(1, MAX_BORDER_CHUNKS))
									.executes(ctx -> setBorder(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "chunks")
									))))
					.then(Commands.literal("pregen")
							.executes(ctx -> startPregen(ctx.getSource(), borderRadiusChunks(ctx.getSource().getLevel())))
							.then(Commands.argument("chunks", IntegerArgumentType.integer(1, MAX_BORDER_CHUNKS))
									.executes(ctx -> startPregen(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "chunks")
									))))
					.then(Commands.literal("scale")
							.executes(ctx -> showScale(ctx.getSource()))
							.then(Commands.argument("size", StringArgumentType.word())
									.suggests(SCALE_SUGGESTIONS)
									.executes(ctx -> setScale(
											ctx.getSource(),
											StringArgumentType.getString(ctx, "size")
									))))
					.then(Commands.literal("auto")
							.executes(ctx -> showAuto(ctx.getSource()))
							.then(Commands.argument("enabled", BoolArgumentType.bool())
									.executes(ctx -> setAuto(
											ctx.getSource(),
											BoolArgumentType.getBool(ctx, "enabled")
									))))
					.then(Commands.literal("pool")
							.executes(ctx -> showPool(ctx.getSource()))));
		});
	}

	private static int runScopeArg(CommandSourceStack source, String raw) {
		WorldScope scope = parseScope(raw);
		if (scope == null) {
			source.sendFailure(Component.literal(
					"Неизвестный мир. Доступны: all, overworld, nether, both"
			));
			return 0;
		}
		return runScope(source, scope);
	}

	private static int runScope(CommandSourceStack source, WorldScope scope) {
		MinecraftServer server = source.getServer();
		BlockShuffleMod.beginExistingSweep();
		int queued = 0;
		List<String> missing = new ArrayList<>();
		for (ResourceKey<Level> key : VANILLA_DIMENSIONS) {
			ServerLevel level = server.getLevel(key);
			if (level == null) {
				if (scope.includes(key)) {
					missing.add(dimensionName(key));
				}
				continue;
			}

			boolean selected = scope.includes(key);
			BlockShuffleState state = BlockShuffleState.get(level);
			state.setAutoEnabled(selected);
			if (!selected) {
				continue;
			}

			state.setScale(BlockShuffleState.get(source.getLevel()).getScale());
			queued += BlockShuffleMod.queueExistingChunks(level);
		}

		int queuedChunks = queued;
		int seconds = Math.max(1, queuedChunks / 20);
		String scale = BlockShuffleState.formatScale(BlockShuffleState.get(source.getLevel()).getScale());
		source.sendSuccess(
				() -> Component.literal(
						"Замена: " + scope.label() + ". Масштаб " + scale + ". Замена " + queuedChunks
								+ " уже сгенерированных чанков, ориентир ~" + formatDuration(seconds)
								+ ". Уже обработанные тоже перезаписываются. Новые — по мере исследования."
								+ (missing.isEmpty() ? "" : " Не загружены: " + String.join(", ", missing) + ".")
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static String dimensionName(ResourceKey<Level> dimension) {
		if (Level.OVERWORLD.equals(dimension)) {
			return "Верхний мир";
		}
		if (Level.NETHER.equals(dimension)) {
			return "Нижний мир";
		}
		if (Level.END.equals(dimension)) {
			return "Энд";
		}
		return dimension.location().toString();
	}

	private static WorldScope parseScope(String raw) {
		String value = raw.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "all", "все", "всё" -> WorldScope.ALL;
			case "overworld", "upper", "верхний", "верх" -> WorldScope.OVERWORLD;
			case "nether", "lower", "нижний", "низ", "ад" -> WorldScope.NETHER;
			case "both", "оба", "нижний+верхний", "верхний+нижний", "nether+overworld", "overworld+nether" -> WorldScope.BOTH;
			default -> null;
		};
	}

	private static int runRadius(CommandSourceStack source, int radius) {
		ServerLevel level = source.getLevel();
		BlockPos center = BlockPos.containing(source.getPosition());
		int replaced = BlockShuffleApplier.applyRadius(level, center, radius);
		source.sendSuccess(
				() -> Component.literal("Случайные блоки: заменено " + replaced + " в радиусе " + radius + "."),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setBorder(CommandSourceStack source, int chunkRadius) {
		ServerLevel level = source.getLevel();
		BlockPos spawn = level.getSharedSpawnPos();
		WorldBorder border = level.getWorldBorder();
		border.setCenter(spawn.getX() + 0.5, spawn.getZ() + 0.5);
		border.setSize(chunkRadius * 16.0 * 2.0);
		border.setWarningBlocks(8);
		int blocks = chunkRadius * 16;
		source.sendSuccess(
				() -> Component.literal(
						"Граница мира: радиус " + chunkRadius + " чанков (" + blocks
								+ " блоков от спавна). Это ванильный worldborder, отдельный мод-барьер не нужен. Преген: /blockshuffle pregen " + chunkRadius
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int startPregen(CommandSourceStack source, int chunkRadius) {
		int total = (2 * chunkRadius + 1) * (2 * chunkRadius + 1);
		int seconds = Math.max(1, total / 40);
		int queued = BlockShuffleMod.startPregen(source.getLevel(), chunkRadius);
		source.sendSuccess(
				() -> Component.literal(
						"Преген " + queued + " чанков (квадрат " + (2 * chunkRadius + 1) + "×" + (2 * chunkRadius + 1)
								+ "). Ориентир: ~" + formatDuration(seconds)
								+ ". Блоки не меняются. Когда прогрузка кончится: /blockshuffle all"
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int borderRadiusChunks(ServerLevel level) {
		double diameterBlocks = level.getWorldBorder().getSize();
		int radius = (int) Math.round(diameterBlocks / 32.0);
		return Math.max(1, Math.min(MAX_BORDER_CHUNKS, radius));
	}

	private static String formatDuration(int seconds) {
		if (seconds < 90) {
			return seconds + " сек";
		}
		if (seconds < 3600) {
			return (seconds / 60) + " мин";
		}
		return (seconds / 3600) + " ч " + ((seconds % 3600) / 60) + " мин";
	}

	private static int showAuto(CommandSourceStack source) {
		boolean on = BlockShuffleState.get(source.getLevel()).isAutoEnabled();
		source.sendSuccess(
				() -> Component.literal(
						"Авторандом в этом измерении: " + (on ? "включён" : "выключен")
								+ ". Запуск миров: /blockshuffle [all|overworld|nether|both]"
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setAuto(CommandSourceStack source, boolean enabled) {
		BlockShuffleState.get(source.getLevel()).setAutoEnabled(enabled);
		source.sendSuccess(
				() -> Component.literal("Авторандом чанков: " + (enabled ? "включён" : "выключен") + "."),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int showScale(CommandSourceStack source) {
		int scale = BlockShuffleState.get(source.getLevel()).getScale();
		source.sendSuccess(
				() -> Component.literal(
								"Масштаб замены: " + BlockShuffleState.formatScale(scale)
								+ ". Варианты: /blockshuffle scale 1|2|4|8|16|chunk|biome"
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setScale(CommandSourceStack source, String raw) {
		Integer parsed = parseScale(raw);
		if (parsed == null) {
			source.sendFailure(Component.literal("Неизвестный масштаб. Доступны: 1, 2, 4, 8, 16, chunk или biome"));
			return 0;
		}

		BlockShuffleState state = BlockShuffleState.get(source.getLevel());
		state.setScale(parsed);
		source.sendSuccess(
				() -> Component.literal(
								"Масштаб замены: " + BlockShuffleState.formatScale(parsed)
								+ ". Потом /blockshuffle all заменит уже сгенерированные чанки заново."
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int showPool(CommandSourceStack source) {
		BlockShuffleApplier.PoolStats stats = BlockShuffleApplier.poolStats();
		source.sendSuccess(
				() -> Component.literal(
						"Полный пул: " + stats.total()
								+ " блоков; обычных " + stats.terrain()
								+ ", сложной формы " + stats.shaped()
								+ ", блоков-сущностей " + stats.blockEntities()
								+ ". Верстак: " + yesNo(stats.craftingTable())
								+ ", улей: " + yesNo(stats.beeNest())
								+ ". Пул действует в Верхнем мире, Аду и Энде."
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static String yesNo(boolean value) {
		return value ? "да" : "нет";
	}

	private static Integer parseScale(String raw) {
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.equals("chunk") || value.equals("чанка") || value.equals("чанк")) {
			return BlockShuffleState.SCALE_CHUNK;
		}
		if (value.equals("biome")) {
			return BlockShuffleState.SCALE_BIOME;
		}
		try {
			int n = Integer.parseInt(value);
			return switch (n) {
				case 1, 2, 4, 8, 16 -> n;
				default -> null;
			};
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
