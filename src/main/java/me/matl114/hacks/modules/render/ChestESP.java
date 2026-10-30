package me.matl114.hacks.modules.render;

import static me.matl114.utils.ColorUtils.*;

import com.mojang.datafixers.util.Pair;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import me.matl114.accessors.access.ChunkAccess;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render2D;
import me.matl114.events.impl.Render3D;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ColorUtils;
import me.matl114.utils.CommonUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.collections.MutableEntry;
import me.matl114.utils.render.RenderCollector;
import me.matl114.utils.world.ContainerPosition;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class ChestESP extends BaseModule {
    public final ModulePath detectBlock = makePath(Configs.RENDER_CONFIG, "detect-block");
    public final ModulePath chestEsp = detectBlock.add("chest-esp");

    private MappedByteBuffer buffer;
    private byte[] bCache = new byte[0xF000];

    private void memoryinit() throws IOException {
        File file = new File(System.getProperty("java.io.tmpdir"), "mc_ipc_shm.dat");

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
                FileChannel channel = raf.getChannel()) {
            buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, 0x200000 * 32);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private BlockPos nearestBlock = null;
    private BlockPos successshownearestBlock = null;
    private boolean nearestBlockInHistory = false;

    public ChestESP() {
        super("ChestESP");
        bindFlag(enable);

        try {
            memoryinit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public final FlagRef enable = flagBuilder(chestEsp.add("enable")).build();

    public final NBTRef<EntrySet<BlockEntityType<?>>> typeFilter = builder(
                    chestEsp.add("enable-types"), EntrySet.<BlockEntityType<?>>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(.*chest|barrel|.*box)$"), Registries.BLOCK_ENTITY_TYPE))
            .build();

    private final ConcurrentHashMap<ContainerPosition, MutableEntry<BlockPos, BlockState>> screens =
            new ConcurrentHashMap<>();

    //    public final Map<BlockPos, BlockEntity> renderPositions = new HashMap<>();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getBlockEntityRenderListener(), this::onBlockEntityRender);
        registerListener(Listener.getPostGameTick(), this::onSwapRenderContent);
        registerListener(RenderListener.getRender2DEvent(), this::onRender2D);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getPostOpenHandledScreen(), this::onOpenHandledScreen);
    }

    public void onOpenHandledScreen(Event<HandledScreen<?>> screenEvent) {
        HandledScreen<?> screen = screenEvent.context();
        if (screen instanceof CreativeInventoryScreen) return;
        Pair<ClientWorld, BlockPos> data;

        ChestHistory.Entry newEntry;
        if (screen instanceof TileInventory tile && !tile.isVirtual()) {
            ContainerPosition containerPosition = tile.getContainerPosition();
            BlockPos pos = tile.getPos();
            var state = mc.world.getBlockState(pos);

            if (containerPosition != null) {
                onAddEntry(containerPosition, state, pos);
            }
        }
    }

    public void onAddEntry(ContainerPosition containerPosition, BlockState state, BlockPos pos) {
        if (containerPosition.isDouble()) {
            removeEntry(ContainerPosition.ofPosition(containerPosition.getFirst()));
            removeEntry(ContainerPosition.ofPosition(containerPosition.getSecond()));
        }
        MutableEntry<BlockPos, BlockState> entry = screens.get(containerPosition);
        if (entry != null) {
            entry.key = pos;
            entry.value = state;
        } else {
            // remove related single chests
            screens.put(containerPosition, new MutableEntry<>(pos, state));
        }
    }

    public void removeEntry(ContainerPosition containerPosition) {
        screens.remove(containerPosition);
        onRemoveEntry(containerPosition);
    }

    public void onRemoveEntry(ContainerPosition containerPosition) {
        screens.remove(containerPosition);
    }

    public void onBlockEntityRender() {}

    public final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector2DTop();
    public final RenderCollector<Vec3d> boxTraceLineCollector = RenderCollectors.createTracerCollector();

    public void onSwapRenderContent() {

        if (checkNull()) return;
        if (mc.player == null) return;

        if (enable.get()) {

            BlockPos bestPos = null;
            double minTanSq = Double.MAX_VALUE; // 记录最小的夹角正切值平方

            var camera = mc.gameRenderer.getCamera();

            Vec3d eyePos = camera.getCameraPos();
            Vec3d lookVec = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());

            int bufferOffset = 0x10100;
            int blockCount = 0;

            for (var chunk : CommonUtils.chunks(false)) {
                for (var blockEntities : ChunkAccess.of(chunk).blockEntityEntries()) {
                    if (typeFilter.get().test(blockEntities.getValue().getType())) {
                        BlockEntity blockEntity = blockEntities.getValue();
                        BlockPos blockPos = blockEntities.getKey();

                        int currentOffset = bufferOffset;
                        bufferOffset += 12;

                        buffer.putInt(currentOffset, blockPos.getX());
                        buffer.putInt(currentOffset + 4, blockPos.getY());
                        buffer.putInt(currentOffset + 8, blockPos.getZ());

                        blockCount++;

                        Vec3d blockCenter = blockPos.toCenterPos();
                        Vec3d toBlock = blockCenter.subtract(eyePos);

                        double parallelDist = toBlock.dotProduct(lookVec);

                        if (parallelDist > 0) {
                            // 2. 计算垂直距离平方与总距离平方
                            double totalDistSq = toBlock.lengthSquared();
                            double perpDistSq = totalDistSq - (parallelDist * parallelDist);

                            // 3. 计算夹角的正切值平方：tan²(θ) = 垂直距离² / 平行距离²
                            // 这个值直接代表了方块在屏幕二维平面上偏离准星的程度，值越小离准星越近
                            double tanSq = perpDistSq / (parallelDist * parallelDist);

                            if (tanSq < minTanSq) {
                                minTanSq = tanSq;
                                bestPos = blockPos;
                            }
                        }
                    }
                }
            }

            buffer.putInt(0x10004, blockCount);
            nearestBlock = bestPos;

            if (nearestBlock != null) {
                for (java.util.Map.Entry<ContainerPosition, MutableEntry<BlockPos, BlockState>> entry :
                        screens.entrySet()) {
                    if (entry.getKey().contains(nearestBlock)) {
                        nearestBlockInHistory = true;
                        return;
                    }
                }
            }

            nearestBlockInHistory = false;
        }
    }

    public void onRender(Event<Render3D> render) {

        boxTraceLineCollector.clear();

        if (enable.get()) {

            int playerX = (int) Math.floor(mc.player.getX());
            int playerY = (int) Math.floor(mc.player.getY());
            int playerZ = (int) Math.floor(mc.player.getZ());

            int worldIndex = 0;

            Dimension d = PlayerUtils.getDimension();
            if (d == Dimension.Nether) {
                worldIndex = 1;
            } else if (d == Dimension.End) {
                worldIndex = 2;
            }

            buffer.putInt(0x10000, worldIndex);
            buffer.putInt(0x10008, playerX);
            buffer.putInt(0x1000C, playerY);
            buffer.putInt(0x10010, playerZ);

            int matchworld = buffer.getInt(0x30000);
            int matchCount = buffer.getInt(0x30004);

            BlockPos.Mutable linepos = new BlockPos.Mutable();

            if (matchworld == worldIndex) {

                int baseOffset = 0x30100;

                // 3. 循环遍历提取 XYZ 坐标
                for (int i = 0; i < matchCount; i++) {
                    int offset = baseOffset + (i * 12); // 每个坐标占用 12 字节 (x:4, y:4, z:4)

                    int x = buffer.getInt(offset);
                    int y = buffer.getInt(offset + 4);
                    int z = buffer.getInt(offset + 8);

                    linepos.set(x, y, z);
                    boxTraceLineCollector.submit(
                            linepos.toCenterPos(),
                            ColorUtils.withAlphaInt(color(Formatting.RED).getRgb(), 1.0F));
                }
            }

            MatrixStack stack = render.context().stack();
            RenderUtils.startDrawVirtual(stack);
            try {
                boxTraceLineCollector.render3D(stack, render.context().partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(stack);
            }
        }
    }

    public void fastSplitLines(String text, List<String> out) {
        out.clear();
        int start = 0;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                // 提取前一行，并顺便去掉 Windows 的 \r
                int end = (i > start && text.charAt(i - 1) == '\r') ? i - 1 : i;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }

        // 【关键修复】只有当 start < len 时才添加最后一行，避免末尾有 \n 时多出空行
        if (start < len) {
            int end = (len > start && text.charAt(len - 1) == '\r') ? len - 1 : len;
            out.add(text.substring(start, end));
        }
    }

    public Text convertItemName(String line) {
        String color = "&f";

        if (line.contains("- 效果")) {
            color = "&7";
        }

        if (line.contains("- 附魔")) {
            color = "&7";
        }

        if (line.contains("诅咒")) {
            color = "&c";
        }

        if (line.contains("金苹果")) {
            color = "&b";
        }

        if (line.contains("钻石")) {
            color = "&b";
        }

        if (line.contains("远古残骸")) {
            color = "&b";
        }

        if (line.contains("下界合金")) {
            color = "&b";
        }

        if (line.contains("附魔金苹果")) {
            color = "&d";
        }

        return ChatUtils.stringToText("%s%s".formatted(color, line));
    }

    public void onRender2D(Event<Render2D> vdraw) {
        textCollector.clear();

        if (enable.get()) {

            if (nearestBlock != null) {

                int blockX = (int) Math.floor(nearestBlock.getX());
                int blockY = (int) Math.floor(nearestBlock.getY());
                int blockZ = (int) Math.floor(nearestBlock.getZ());

                int worldIndex = 0;

                meteordevelopment.meteorclient.utils.world.Dimension d = PlayerUtils.getDimension();
                if (d == meteordevelopment.meteorclient.utils.world.Dimension.Nether) {
                    worldIndex = 1;
                } else if (d == Dimension.End) {
                    worldIndex = 2;
                }

                buffer.putInt(0x0, blockX);
                buffer.putInt(0x4, blockY);
                buffer.putInt(0x8, blockZ);
                buffer.putInt(0xC, worldIndex);

                int remoteX = buffer.getInt(0x100);
                int remoteY = buffer.getInt(0x104);
                int remoteZ = buffer.getInt(0x108);
                int remoteworldIndex = buffer.getInt(0x10C);

                if (blockX == remoteX && blockY == remoteY && blockZ == remoteZ && worldIndex == remoteworldIndex) {

                    int bLen = buffer.getInt(0x110);
                    if (bLen > 0) {
                        buffer.get(0x200, bCache, 0, bLen);

                        String s = new String(bCache, 0, bLen, StandardCharsets.UTF_8);

                        var camera = mc.gameRenderer.getCamera();
                        Vec3d eyePos = camera.getCameraPos();
                        double len = eyePos.distanceTo(nearestBlock.toCenterPos());

                        List<String> textLines = new ArrayList<>();
                        fastSplitLines(s, textLines);

                        MutableText result = Text.empty().append(convertItemName(textLines.getFirst()));
                        for (int i = 1; i < textLines.size(); i++) {
                            result = result.append(Text.literal("\n")).append(convertItemName(textLines.get(i)));
                        }

                        if (nearestBlockInHistory) {
                            result = result.append(Text.literal("\n"))
                                    .append(ChatUtils.stringToText("&e🌟&f[" + "%.2f".formatted(len) + "]"));
                        } else {
                            result = result.append(Text.literal("\n"))
                                    .append(ChatUtils.stringToText("&f[" + "%.2f".formatted(len) + "]"));
                        }

                        Vec3d textPos = nearestBlock.toCenterPos();
                        textCollector.submit(
                                new RenderElements.Text(result, textPos, 0.75F),
                                ColorUtils.withAlphaInt(color(Formatting.WHITE).getRgb(), 1.0F));

                        successshownearestBlock = nearestBlock;
                    }
                }

                vdraw.context.drawContext().pushMatrix();
                try {
                    textCollector.render2D(
                            vdraw.context.drawContext(), vdraw.context().partialTicks());
                } finally {
                    vdraw.context.drawContext().popMatrix();
                }
            }
        }
    }

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor,
            int dx,
            int dy,
            int dblank,
            me.matl114.gui.interfaces.ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
        acceptor.accept(factory.createExecuteButton(
                "Clear cache",
                ButtonAction.run(() -> {
                    screens.clear();
                }),
                0,
                dblank,
                dx,
                dy));
    }
}
