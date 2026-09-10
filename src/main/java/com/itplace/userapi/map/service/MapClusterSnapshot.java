package com.itplace.userapi.map.service;

import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 완성된 집계만 게시한다. 인덱스는 같은 Region을 참조하며 요청 결과를 보관하지 않는다. */
final class MapClusterSnapshot {
    private static final Set<String> UNITS = Set.of("LEGAL_DONG", "TOWN", "CITY");
    private static final Comparator<StoreClusterProjection> ORDER = Comparator
            .comparing(StoreClusterProjection::getCount, Comparator.reverseOrder())
            .thenComparing(StoreClusterProjection::getAdministrativeUnitType)
            .thenComparing(StoreClusterProjection::getClusterId);

    final Data data;
    final int jsonBytes;
    private final Map<String, Map<Long, List<Region>>> tiles = new HashMap<>();

    MapClusterSnapshot(Data data, int jsonBytes, int maxRegions, int maxCategoryCounts) {
        if (data.schema() != 1 || data.version() == null || data.version().length() > 100
                || data.createdAt() <= 0 || data.regions().size() > maxRegions) {
            throw new IllegalArgumentException("지도 snapshot metadata/크기 오류");
        }
        int categoryCount = 0;
        for (Region region : data.regions()) {
            if (!UNITS.contains(region.unit()) || !UNITS.contains(region.type())
                    || !Double.isFinite(region.lat()) || region.lat() < -90 || region.lat() > 90
                    || !Double.isFinite(region.lng()) || region.lng() < -180 || region.lng() > 180
                    || !validText(region.hash(), 128, false) || !validText(region.key(), 300, false)
                    || !validText(region.name(), 300, true) || region.counts().isEmpty()) {
                throw new IllegalArgumentException("지도 snapshot 구역 오류");
            }
            long total = 0;
            for (var entry : region.counts().entrySet()) {
                CategoryCount count = entry.getValue();
                if (!validText(entry.getKey(), 200, false) || count.count() < 0
                        || !validText(count.name(), 300, true) || ++categoryCount > maxCategoryCounts) {
                    throw new IllegalArgumentException("지도 snapshot category/크기 오류");
                }
                total = Math.addExact(total, count.count());
            }
            if (total != region.total()) throw new IllegalArgumentException("지도 snapshot 합계 오류");
            tiles.computeIfAbsent(region.unit(), ignored -> new HashMap<>())
                    .computeIfAbsent(tile(region.unit(), region.lat(), region.lng()), ignored -> new ArrayList<>())
                    .add(region);
        }
        this.data = data;
        this.jsonBytes = jsonBytes;
    }

    List<StoreClusterProjection> select(String unit, double minLat, double maxLat,
                                       double minLng, double maxLng, String category, int mapLevel) {
        Map<Long, List<Region>> unitTiles = tiles.get(unit);
        if (unitTiles == null) return List.of();
        List<StoreClusterProjection> result = new ArrayList<>();
        if (unit.equals("CITY")) {
            collect(unitTiles.get(0L), result, minLat, maxLat, minLng, maxLng, category, mapLevel);
        } else {
            double step = step(unit);
            int minY = (int) Math.floor(Math.max(-90, minLat) / step);
            int maxY = (int) Math.floor(Math.min(90, maxLat) / step);
            int minX = (int) Math.floor(Math.max(-180, minLng) / step);
            int maxX = (int) Math.floor(Math.min(180, maxLng) / step);
            long cells = ((long) maxY - minY + 1) * ((long) maxX - minX + 1);
            if (minY > maxY || minX > maxX) return List.of();
            // ponytail: 넓은 범위에서는 빈 타일을 수백만 번 탐색하지 않고 보관한 구획만 순회한다.
            if (cells > unitTiles.size()) {
                unitTiles.values().forEach(rows -> collect(rows, result, minLat, maxLat, minLng, maxLng, category, mapLevel));
            } else {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        collect(unitTiles.get(key(y, x)), result, minLat, maxLat, minLng, maxLng, category, mapLevel);
                    }
                }
            }
        }
        result.sort(ORDER);
        return result;
    }

    private static void collect(List<Region> rows, List<StoreClusterProjection> result,
                                double minLat, double maxLat, double minLng, double maxLng,
                                String category, int mapLevel) {
        if (rows == null) return;
        for (Region region : rows) {
            if (region.lat() < minLat || region.lat() > maxLat || region.lng() < minLng || region.lng() > maxLng) continue;
            CategoryCount selected = category == null ? null : region.counts().get(category);
            if (category != null && selected == null) continue;
            result.add(new Cluster("a:" + mapLevel + ":" + region.type() + ":" + region.hash(),
                    category == null ? "전체" : category, region.type(),
                    selected == null ? region.name() : selected.name(), region.lat(), region.lng(),
                    selected == null ? region.total() : selected.count()));
        }
    }

    private static boolean validText(String value, int limit, boolean nullable) {
        return value == null ? nullable : value.length() <= limit;
    }

    private static double step(String unit) { return unit.equals("LEGAL_DONG") ? 0.1 : 0.25; }
    private static long tile(String unit, double lat, double lng) {
        return unit.equals("CITY") ? 0 : key((int) Math.floor(lat / step(unit)), (int) Math.floor(lng / step(unit)));
    }
    private static long key(int y, int x) { return ((long) y << 32) ^ (x & 0xffffffffL); }

    public record Data(int schema, String version, long createdAt, List<Region> regions) {
        public Data { regions = List.copyOf(regions); }
    }
    public record Region(String unit, String type, String hash, String key, String name,
                         double lat, double lng, long total, Map<String, CategoryCount> counts) {
        public Region { counts = Map.copyOf(counts); }
    }
    public record CategoryCount(String name, long count) {}

    @Getter
    @AllArgsConstructor
    private static final class Cluster implements StoreClusterProjection {
        private final String clusterId;
        private final String category;
        private final String administrativeUnitType;
        private final String administrativeUnitName;
        private final Double latitude;
        private final Double longitude;
        private final Long count;
    }
}
