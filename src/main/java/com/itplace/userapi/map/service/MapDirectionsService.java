package com.itplace.userapi.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoDirectionsClient;
import com.itplace.userapi.map.client.KakaoDirectionsClient.DirectionsProviderException;
import com.itplace.userapi.map.dto.MapDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse;
import com.itplace.userapi.map.dto.CoordinateDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse.Coordinate;
import com.itplace.userapi.map.dto.MapDirectionsResponse.Destination;
import com.itplace.userapi.map.dto.MapDirectionsResponse.Route;
import com.itplace.userapi.map.dto.MapDirectionsResponse.Segment;
import com.itplace.userapi.map.dto.MapDirectionsResponse.SegmentMode;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.StoreRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MapDirectionsService {
    private static final int MAX_ROUTES = 3;

    private final StoreRepository storeRepository;
    private final KakaoDirectionsClient directionsClient;

    public MapDirectionsResponse directions(long storeId, MapDirectionsRequest request) {
        Store store = storeRepository.findActiveStoreWithPartner(storeId)
                .orElseThrow(() -> new DirectionsProviderException(StoreCode.STORE_NOT_FOUND));
        double destinationLat = coordinate(store.getLatitude() == null ? null : store.getLatitude().doubleValue(),
                store.getLocation() == null ? Double.NaN : store.getLocation().getY());
        double destinationLng = coordinate(store.getLongitude() == null ? null : store.getLongitude().doubleValue(),
                store.getLocation() == null ? Double.NaN : store.getLocation().getX());
        if (!validCoordinate(destinationLat, -90, 90) || !validCoordinate(destinationLng, -180, 180)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }

        String name = StringUtils.hasText(store.getStoreName()) ? store.getStoreName() : "매장 " + storeId;
        return directions(request.mode(), request.origin().lat(), request.origin().lng(),
                new Destination(storeId, name, destinationLat, destinationLng));
    }

    public MapDirectionsResponse directions(CoordinateDirectionsRequest request) {
        CoordinateDirectionsRequest.Destination destination = request.destination();
        double destinationLat = destination.lat();
        double destinationLng = destination.lng();
        if (!validCoordinate(destinationLat, -90, 90) || !validCoordinate(destinationLng, -180, 180)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_INVALID_INPUT);
        }
        String name = StringUtils.hasText(destination.name()) ? destination.name().strip() : "도착지";
        return directions(request.mode(), request.origin().lat(), request.origin().lng(),
                new Destination(null, name, destinationLat, destinationLng));
    }

    private MapDirectionsResponse directions(MapDirectionsRequest.Mode mode, double originLat, double originLng,
            Destination destination) {
        JsonNode response = switch (mode) {
            case WALK -> directionsClient.walk(originLat, originLng, destination.lat(), destination.lng());
            case TRANSIT -> directionsClient.transit(originLat, originLng, destination.lat(), destination.lng());
            case CAR -> directionsClient.car(originLat, originLng, destination.lat(), destination.lng());
        };
        List<Route> routes = switch (mode) {
            case WALK -> parseWalk(response);
            case TRANSIT -> parseTransit(response);
            case CAR -> parseCar(response);
        };
        return new MapDirectionsResponse(mode, new Coordinate(originLat, originLng), destination, routes);
    }

    private List<Route> parseWalk(JsonNode root) {
        String status = text(root, "status");
        if ("ROUTE_RESULT_NOT_FOUND".equals(status)) {
            return List.of();
        }
        if ("SAME_POINT".equals(status)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_INVALID_INPUT);
        }
        requireStatus(status, "OK");
        JsonNode route = required(root, "route");
        JsonNode properties = required(route, "properties");
        List<Segment> segments = new ArrayList<>();
        for (JsonNode leg : requiredArray(route, "legs")) {
            for (JsonNode step : requiredArray(leg, "steps")) {
                JsonNode stepProperties = required(step, "properties");
                segments.add(new Segment(SegmentMode.WALK, optionalText(stepProperties, "guidance"),
                        optionalNonnegativeInt(stepProperties, "distance"),
                        optionalNonnegativeInt(stepProperties, "time"),
                        pairGeometry(required(required(step, "path"), "points"))));
            }
        }
        requireSegments(segments);
        return List.of(new Route("walk-1", nonnegativeInt(properties, "totalDistance"),
                nonnegativeInt(properties, "totalTime"), null, List.copyOf(segments)));
    }

    private List<Route> parseTransit(JsonNode root) {
        String status = text(root, "status");
        if ("NO_RESULTS".equals(status)) {
            return List.of();
        }
        if ("EQUAL_POINTS".equals(status)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_INVALID_INPUT);
        }
        requireStatus(status, "OK");
        JsonNode routesNode = requiredArray(root, "routes");
        List<Route> routes = new ArrayList<>();
        for (JsonNode routeNode : routesNode) {
            if (routes.size() == MAX_ROUTES) {
                break;
            }
            JsonNode properties = required(routeNode, "properties");
            List<Segment> segments = new ArrayList<>();
            for (JsonNode step : requiredArray(routeNode, "steps")) {
                JsonNode stepProperties = required(step, "properties");
                SegmentMode mode = transitMode(text(stepProperties, "type"));
                String label = optionalText(stepProperties, "guidance");
                if (!StringUtils.hasText(label)) {
                    label = vehicleName(stepProperties);
                }
                // Preserve each provider path separately; joining step endpoints would invent transit geometry.
                segments.add(new Segment(mode, label, optionalNonnegativeInt(stepProperties, "distance"),
                        optionalNonnegativeInt(stepProperties, "time"),
                        pairGeometry(required(required(step, "path"), "points"))));
            }
            requireSegments(segments);
            routes.add(new Route("transit-" + (routes.size() + 1), nonnegativeInt(properties, "totalDistance"),
                    nonnegativeInt(properties, "totalTime"), null, List.copyOf(segments)));
        }
        if (routesNode.isEmpty()) {
            return List.of();
        }
        if (routes.isEmpty()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return List.copyOf(routes);
    }

    private List<Route> parseCar(JsonNode root) {
        JsonNode routeNodes = requiredArray(root, "routes");
        List<Route> routes = new ArrayList<>();
        for (JsonNode routeNode : routeNodes) {
            int resultCode = nonnegativeInt(routeNode, "result_code");
            if (resultCode == 1) {
                continue;
            }
            if (resultCode != 0) {
                throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
            }
            if (routes.size() == MAX_ROUTES) {
                break;
            }
            JsonNode summary = required(routeNode, "summary");
            List<Segment> segments = new ArrayList<>();
            for (JsonNode section : requiredArray(routeNode, "sections")) {
                for (JsonNode road : requiredArray(section, "roads")) {
                    segments.add(new Segment(SegmentMode.CAR, optionalText(road, "name"),
                            optionalNonnegativeInt(road, "distance"), optionalNonnegativeInt(road, "duration"),
                            flatGeometry(required(road, "vertexes"))));
                }
            }
            requireSegments(segments);
            Integer toll = optionalNonnegativeInt(required(summary, "fare"), "toll");
            routes.add(new Route("car-" + (routes.size() + 1), nonnegativeInt(summary, "distance"),
                    nonnegativeInt(summary, "duration"), toll, List.copyOf(segments)));
        }
        return List.copyOf(routes);
    }

    private List<Coordinate> pairGeometry(JsonNode points) {
        if (!points.isArray() || points.size() < 2) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        List<Coordinate> geometry = new ArrayList<>(points.size());
        for (JsonNode point : points) {
            if (!point.isArray() || point.size() < 2) {
                throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
            }
            geometry.add(coordinateFromXY(point.get(0), point.get(1)));
        }
        return List.copyOf(geometry);
    }

    private List<Coordinate> flatGeometry(JsonNode vertexes) {
        if (!vertexes.isArray() || vertexes.size() < 4 || vertexes.size() % 2 != 0) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        List<Coordinate> geometry = new ArrayList<>(vertexes.size() / 2);
        for (int index = 0; index < vertexes.size(); index += 2) {
            geometry.add(coordinateFromXY(vertexes.get(index), vertexes.get(index + 1)));
        }
        return List.copyOf(geometry);
    }

    private Coordinate coordinateFromXY(JsonNode x, JsonNode y) {
        if (x == null || y == null || !x.isNumber() || !y.isNumber()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        double lng = x.asDouble();
        double lat = y.asDouble();
        if (!validCoordinate(lat, -90, 90) || !validCoordinate(lng, -180, 180)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return new Coordinate(lat, lng);
    }

    private SegmentMode transitMode(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "BUS" -> SegmentMode.BUS;
            case "SUBWAY" -> SegmentMode.SUBWAY;
            case "WALKING" -> SegmentMode.WALK;
            default -> SegmentMode.OTHER;
        };
    }

    private String vehicleName(JsonNode properties) {
        JsonNode vehicles = properties.path("vehicles");
        if (!vehicles.isArray() || vehicles.isEmpty()) {
            return null;
        }
        return vehicles.get(0).path("name").asText(null);
    }

    private void requireSegments(List<Segment> segments) {
        if (segments.isEmpty()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
    }

    private void requireStatus(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
    }

    private JsonNode required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isArray()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return value;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isTextual()) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return value.asText();
    }

    private String optionalText(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isTextual() ? value.asText() : null;
    }

    private int nonnegativeInt(JsonNode node, String name) {
        Integer value = optionalNonnegativeInt(node, name);
        if (value == null) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return value;
    }

    private Integer optionalNonnegativeInt(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw new DirectionsProviderException(StoreCode.DIRECTIONS_PROVIDER_ERROR);
        }
        return value.asInt();
    }

    private double coordinate(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private boolean validCoordinate(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }
}
