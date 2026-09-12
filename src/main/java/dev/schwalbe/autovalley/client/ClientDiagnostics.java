package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.ui.RegistrationRules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicitly requested local observations; never issues gameplay or registration actions. */
public final class ClientDiagnostics {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final long POLL_NANOS = 1_000_000_000L;
    private static final int RADIUS = 32, VERTICAL = 16, MAX_CANDIDATES = 256, MAX_PER_KIND = 512, MAX_MENU_SLOTS = 256;
    private static long nextPoll;
    private static boolean errorReported;

    private record Candidate(String kind, Pos pos, String id, Map<String,String> properties) { }
    private record CandidateGroup(int count, boolean truncated, List<Candidate> candidates) { }
    private record AimedBlock(Pos pos, String id, Map<String,String> properties, double distance) { }
    private record CropCluster(Pos first, Pos second, int blocks, int matureBlocks) { }
    private record UiFieldSuggestion(Pos first, Pos second, boolean partial,String cropId) { }

    private ClientDiagnostics() { }

    /** Invoke on the client thread, including title/menu screens when no world is joined. */
    public static void tick(ClientRuntime runtime) {
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + POLL_NANOS;
        Path directory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve("autovalley");
        Path request = directory.resolve("inspect.request");
        try {
            if (!Files.isRegularFile(request,LinkOption.NOFOLLOW_LINKS)) return;
            // Consume only this one explicit request; its contents are never evaluated.
            if (!Files.deleteIfExists(request)) return;
            Map<String,Object> snapshot = snapshot(runtime);
            atomicWrite(directory,snapshot);
        } catch (IOException | RuntimeException e) {
            if (!errorReported) {
                errorReported = true;
                LogUtils.getLogger().warn("Auto Valley local inspection failed once: {}",e.getClass().getSimpleName());
            }
        }
    }

    private static Map<String,Object> snapshot(ClientRuntime runtime) {
        Minecraft mc = Minecraft.getInstance();
        WorldAccess world = runtime.world();
        PlayerState player = world.player();
        boolean connected = player != null && player.connected() && mc.player != null && mc.level != null;
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schemaVersion",1);
        String capturedAt=Instant.now().toString();
        report.put("capturedAt",capturedAt);
        report.put("connected",connected);
        report.put("running",runtime.running());
        report.put("executionMode",runtime.executionMode());
        report.put("recording",runtime.recording());
        report.put("recordingActive",runtime.recordingActive());
        report.put("recordingStatus",runtime.recordingStatus());
        report.put("status",runtime.status());
        report.put("wineLineEditRejection",runtime.wineLineSettingsRejection());
        report.put("failureHistory",runtime.failureHistory());
        report.put("crystals",runtime.crystalReport());
        Map<String,Object> persistence=runtime.persistenceReport();
        report.put("persistence",persistence);
        report.put("profileEvidence",ProfileEvidenceSnapshot.capture(runtime.profile(),runtime.diagnosticProfileKey(),
            capturedAt,persistence));
        Profile loggingProfile=runtime.profile();
        Map<String,Object> logging=new LinkedHashMap<>();
        logging.put("enabled",loggingProfile.enabled(Feature.LOGGING));
        logging.put("active",loggingProfile.loggingRunActive);
        logging.put("remainingPlots",List.copyOf(loggingProfile.loggingRemainingPlots));
        logging.put("replantingPlots",List.copyOf(loggingProfile.loggingReplantingPlots));
        logging.put("hotbarLease",loggingProfile.loggingHotbarLease);
        logging.put("nextEligibleDay",loggingProfile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        logging.put("registeredPlotCount",loggingProfile.loggingPlots.size());
        logging.put("mode",loggingProfile.loggingMode);
        logging.put("growth",ProfileEvidenceSnapshot.loggingGrowth(world,loggingProfile,connected));
        if (ManualLoggingHotbarResolution.validLease(loggingProfile.loggingHotbarLease)) {
            String key=ManualLoggingHotbarResolution.confirmationKey(loggingProfile.loggingHotbarLease);
            logging.put("manualConfirmationKey",key);
            logging.put("manualConfirmationRejection",runtime.manualLoggingHotbarConfirmationRejection(key));
        }
        report.put("logging",logging);
        var workLease=runtime.profile().workHotbarLease;
        report.put("workHotbar",workLease==null ? Map.of() : Map.of("owner",workLease.owner().name(),
            "stage",workLease.stage().name(),"hotbarSlot",workLease.hotbarSlot(),"parkedInventorySlot",workLease.sourceIndex()));
        report.put("navigation",runtime.navigationReport());
        report.put("storageSurvey",runtime.storageSurveyReport());
        report.put("pendingShipment",runtime.pendingShipmentReport());
        report.put("screenClass",mc.screen == null ? null : mc.screen.getClass().getName());
        report.put("screenTitle",mc.screen == null ? null : mc.screen.getTitle().getString());
        if (connected) {
            Map<String,Object> observed = new LinkedHashMap<>();
            observed.put("x",player.x()); observed.put("y",player.y()); observed.put("z",player.z());
            observed.put("yaw",player.yaw()); observed.put("pitch",player.pitch());
            observed.put("health",player.health()); observed.put("food",player.food());
            observed.put("focused",player.focused());
            observed.put("dayTime",world.dayTime()); observed.put("selectedHotbar",player.selectedSlot());
            observed.put("wineCalendarYear",world.wineYear());
            observed.put("heldDisplayName",mc.player.getMainHandItem().isEmpty() ? null : mc.player.getMainHandItem().getHoverName().getString());
            report.put("player",observed);
            report.put("aimedBlock",aimedBlock(mc,world));
            report.put("inventory",world.inventory().stream().limit(36).toList());
            report.put("groundItems",world.groundItems().stream().limit(512).toList());
            MenuData menu = world.menu();
            Map<String,Object> menuReport = new LinkedHashMap<>();
            if (menu != null) {
                menuReport.put("class",mc.player.containerMenu.getClass().getName());
                menuReport.put("id",menu.id()); menuReport.put("revision",menu.revision());
                menuReport.put("container",menu.container()); menuReport.put("carried",menu.carried());
                menuReport.put("slotCount",menu.slots().size());
                menuReport.put("slots",menu.slots().stream().limit(MAX_MENU_SLOTS).toList());
                menuReport.put("truncated",menu.slots().size() > MAX_MENU_SLOTS);
            }
            report.put("menu",menu == null ? null : menuReport);
            report.put("nearby",nearby(world,player.feet(),runtime.profile()));
        } else {
            report.put("player",null);
            report.put("aimedBlock",null);
            report.put("inventory",List.of());
            report.put("menu",null);
            report.put("nearby",null);
        }
        Profile profile = runtime.profile();
        Map<String,Object> registration = new LinkedHashMap<>();
        registration.put("farmCount",profile.farms.size());
        registration.put("poiCount",profile.pois.size());
        Map<String,Long> farmsByCrop=new TreeMap<>();
        for(Farm farm:profile.farms)farmsByCrop.merge(farm.cropId(),1L,Long::sum);
        registration.put("farmsByCrop",farmsByCrop);
        registration.put("commodityStoreCount",profile.commodityStores.size());
        registration.put("artisanJobCount",profile.artisanJobs.size());
        List<Map<String,Object>> wineLines=new ArrayList<>();
        for(WineProductionLine line:WineProductionRules.lines(profile)) {
            Map<String,Object> details=new LinkedHashMap<>();
            details.put("id",line.id());details.put("name",line.name());details.put("enabled",line.enabled());
            details.put("inputItem",line.inputItemId());details.put("outputItem",line.outputItemId());
            details.put("machineCount",line.machines().size());details.put("cycleDays",line.cycleDays());
            CommodityStore input=WineProductionRules.inputStore(profile,line),output=WineProductionRules.outputStore(profile,line);
            details.put("inputContainerCount",input==null?profile.pois(PoiKind.TOMATO_CHEST).size():input.containers().size());
            details.put("outputContainerCount",output==null?profile.pois(PoiKind.WINE_CHEST).size():output.containers().size());
            WineBatchSchedule schedule=WineBatchRules.schedule(profile,line.id());
            details.put("nextDueDay",schedule==null?null:schedule.nextDueDay());
            details.put("active",schedule!=null && schedule.active());details.put("remaining",schedule==null?0:schedule.remaining().size());
            wineLines.add(details);
        }
        registration.put("wineProductionLines",wineLines);
        registration.put("fruitPatchCount",profile.fruitPatches.size());
        registration.put("orchardDrafts",profile.orchardDrafts.stream().map(draft -> Map.of(
            "id",draft.id(),"name",draft.name(),"observedFruitCount",draft.observedFruits().size(),
            "toolItemId",draft.toolItemId(),"outputItemId",draft.outputItemId(),
            "executionReady",false,"status","STORAGE_NOT_REGISTERED_DRAFT_ONLY")).toList());
        Map<String,Integer> byKind = new LinkedHashMap<>();
        for (PoiKind kind : PoiKind.values()) byKind.put(kind.name(),profile.pois(kind).size());
        registration.put("poisByKind",byKind);
        report.put("registrations",registration);
        return report;
    }

    private static AimedBlock aimedBlock(Minecraft mc, WorldAccess world) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        Pos pos = new Pos(hit.getBlockPos().getX(),hit.getBlockPos().getY(),hit.getBlockPos().getZ());
        if (!world.loaded(pos)) return null;
        BlockData block = world.block(pos);
        return new AimedBlock(pos,block.id(),new TreeMap<>(block.properties()),mc.player.getEyePosition().distanceTo(hit.getLocation()));
    }

    private static Map<String,Object> nearby(WorldAccess world, Pos center,Profile profile) {
        List<Candidate> recognized = new ArrayList<>();
        Map<Pos,BlockData> crops = new HashMap<>();
        List<BlockData> farmCrops=new ArrayList<>();
        Map<String,Integer> blockCounts = new TreeMap<>();
        for (BlockData block : world.scan(center,RADIUS,VERTICAL,CropRules.scanBlockIds(profile))) {
            String kind = kind(block,profile);
            if (kind == null) continue;
            recognized.add(new Candidate(kind,block.pos(),block.id(),new TreeMap<>(block.properties())));
            blockCounts.merge(block.id(),1,Integer::sum);
            if (block.tomato()) crops.put(block.pos(),block);
            if (RegistrationRules.crop(profile,block)!=null)farmCrops.add(block);
        }
        // Preserve machine/container details first; crop cluster bounds summarize large fields.
        recognized.sort(Comparator.comparingInt((Candidate c) -> c.kind().equals("tomato") ? 1 : 0)
            .thenComparingDouble(c -> c.pos().distanceSquared(center))
            .thenComparingInt(c -> c.pos().x()).thenComparingInt(c -> c.pos().y()).thenComparingInt(c -> c.pos().z()));
        List<CropCluster> clusters = clusters(crops);
        Map<String,Object> scan = new LinkedHashMap<>();
        scan.put("center",center); scan.put("horizontalRadius",RADIUS); scan.put("verticalRadius",VERTICAL);
        scan.put("recognizedCount",recognized.size()); scan.put("blockCounts",blockCounts);
        scan.put("candidates",recognized.stream().limit(MAX_CANDIDATES).toList());
        scan.put("candidatesTruncated",recognized.size() > MAX_CANDIDATES);
        Map<String,List<Candidate>> perKind = new TreeMap<>();
        for (String kind : List.of("tomato","wine_keg","preserves_jar","shipping_bin","bed","container")) perKind.put(kind,new ArrayList<>());
        for (Candidate candidate : recognized) perKind.computeIfAbsent(candidate.kind(),ignored -> new ArrayList<>()).add(candidate);
        Comparator<Candidate> nearest = Comparator.comparingDouble((Candidate c) -> c.pos().distanceSquared(center))
            .thenComparingInt(c -> c.pos().x()).thenComparingInt(c -> c.pos().y()).thenComparingInt(c -> c.pos().z());
        Map<String,CandidateGroup> groupedCandidates = new TreeMap<>();
        perKind.forEach((kind,items) -> {
            items.sort(nearest);
            groupedCandidates.put(kind,new CandidateGroup(items.size(),items.size() > MAX_PER_KIND,items.stream().limit(MAX_PER_KIND).toList()));
        });
        scan.put("maxCandidatesPerKind",MAX_PER_KIND);
        scan.put("candidatesByKind",groupedCandidates);
        scan.put("tomatoClusterCount",clusters.size());
        scan.put("tomatoClusters",clusters.stream().limit(32).toList());
        scan.put("tomatoClustersTruncated",clusters.size() > 32);
        List<Farm> uiFields = RegistrationRules.suggestFarms(farmCrops,profile);
        scan.put("uiFieldSuggestionCount",uiFields.size());
        scan.put("uiFieldSuggestions",uiFields.stream().limit(32).map(farm -> new UiFieldSuggestion(farm.first(),farm.second(),
            RegistrationRules.mayBePartial(farm,center,RADIUS,VERTICAL,world::loaded),farm.cropId())).toList());
        scan.put("uiFieldSuggestionsTruncated",uiFields.size() > 32);
        return scan;
    }

    private static String kind(BlockData block,Profile profile) {
        CropDefinition crop=RegistrationRules.crop(profile,block);
        if (crop!=null) return crop.key();
        if (block.id().equals(ArtisanRecipe.ANCIENT_SEED.machineId()))return "seed_maker";
        if (block.id().equals(CrystalCollection.MACHINE_ID))return "crystalarium";
        if (block.id().equals(FruitRules.BLOCK))return "starfruit";
        if (block.id().contains("wine_keg")) return "wine_keg";
        if (block.id().contains("preserves_jar")) return "preserves_jar";
        if (block.id().contains("shipping_bin")) return "shipping_bin";
        if (block.id().endsWith("_bed")) return "bed";
        if (block.flag("container")) return "container";
        return null;
    }

    /** Connected crop blocks are candidates, not asserted user farm registrations. */
    private static List<CropCluster> clusters(Map<Pos,BlockData> crops) {
        Set<Pos> remaining = new HashSet<>(crops.keySet());
        List<CropCluster> result = new ArrayList<>();
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!remaining.isEmpty()) {
            Pos first = remaining.iterator().next();
            remaining.remove(first);
            Deque<Pos> queue = new ArrayDeque<>(); queue.add(first);
            int minX=first.x(),maxX=first.x(),minY=first.y(),maxY=first.y(),minZ=first.z(),maxZ=first.z(),count=0,mature=0;
            while (!queue.isEmpty()) {
                Pos p = queue.removeFirst(); count++;
                if (crops.get(p).matureTomato()) mature++;
                minX=Math.min(minX,p.x()); maxX=Math.max(maxX,p.x());
                minY=Math.min(minY,p.y()); maxY=Math.max(maxY,p.y());
                minZ=Math.min(minZ,p.z()); maxZ=Math.max(maxZ,p.z());
                for (int[] d : directions) {
                    Pos neighbor = p.offset(d[0],d[1],d[2]);
                    if (remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
            result.add(new CropCluster(new Pos(minX,minY,minZ),new Pos(maxX,maxY,maxZ),count,mature));
        }
        result.sort(Comparator.comparingInt(CropCluster::blocks).reversed()
            .thenComparingInt(c -> c.first().x()).thenComparingInt(c -> c.first().y()).thenComparingInt(c -> c.first().z()));
        return result;
    }

    private static void atomicWrite(Path directory, Map<String,Object> snapshot) throws IOException {
        Path temporary = Files.createTempFile(directory,"diagnostics-",".tmp");
        try {
            Files.writeString(temporary,GSON.toJson(snapshot),StandardCharsets.UTF_8);
            Files.move(temporary,directory.resolve("diagnostics.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
