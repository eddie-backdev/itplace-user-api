package com.itplace.userapi.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.client.KakaoLocalSearchClient;
import com.itplace.userapi.map.client.KakaoLocalSearchClient.LocalSearchException;
import com.itplace.userapi.map.dto.LocationSearchRequest;
import com.itplace.userapi.map.dto.LocationSearchResponse;
import com.itplace.userapi.map.dto.LocationSearchResponse.Kind;
import com.itplace.userapi.map.dto.LocationSearchResponse.Location;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LocationSearchService {
    private static final int MAX_RESULTS = 10;
    private final KakaoLocalSearchClient kakaoLocalSearchClient;

    public LocationSearchResponse search(LocationSearchRequest request) {
        String query = request.query().strip();
        Double centerLat = request.center() == null ? null : request.center().lat();
        Double centerLng = request.center() == null ? null : request.center().lng();
        List<Location> addresses = null;
        List<Location> places = null;
        LocalSearchException addressFailure = null;
        LocalSearchException placeFailure = null;

        try {
            addresses = parseAddresses(kakaoLocalSearchClient.address(query));
        } catch (LocalSearchException e) {
            addressFailure = e;
        }
        try {
            places = parsePlaces(kakaoLocalSearchClient.places(query, centerLat, centerLng));
        } catch (LocalSearchException e) {
            placeFailure = e;
        }
        if (addresses == null && places == null) {
            throw combinedFailure(addressFailure, placeFailure);
        }

        return new LocationSearchResponse(merge(addresses == null ? List.of() : addresses,
                places == null ? List.of() : places, addressFirst(query)), addressFailure != null || placeFailure != null);
    }

    private List<Location> parseAddresses(JsonNode root) {
        List<JsonNode> documents = documents(root);
        List<Location> result = new ArrayList<>();
        for (JsonNode doc : documents) {
            String name = boundedName(text(doc, "address_name"));
            if (!StringUtils.hasText(name)) {
                throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
            }
            double lng = coordinate(doc, "x");
            double lat = coordinate(doc, "y");
            String address = nestedText(doc, "address", "address_name");
            if (!StringUtils.hasText(address)) {
                address = name;
            }
            String roadAddress = nestedText(doc, "road_address", "address_name");
            result.add(new Location("address:" + UUID.randomUUID(), name, address, roadAddress, lat, lng,
                    Kind.ADDRESS, null));
        }
        return result;
    }

    private List<Location> parsePlaces(JsonNode root) {
        List<JsonNode> documents = documents(root);
        List<Location> result = new ArrayList<>();
        for (JsonNode doc : documents) {
            String name = boundedName(text(doc, "place_name"));
            String providerId = text(doc, "id");
            if (!StringUtils.hasText(name) || !StringUtils.hasText(providerId)) {
                continue;
            }
            String distance = text(doc, "distance");
            Integer distanceMeters = null;
            if (StringUtils.hasText(distance)) {
                try {
                    int parsed = Integer.parseInt(distance);
                    if (parsed >= 0) {
                        distanceMeters = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Provider distance is optional; omit malformed metadata without changing the candidate.
                }
            }
            result.add(new Location("place:" + providerId, name, nullableText(doc, "address_name"),
                    nullableText(doc, "road_address_name"), coordinate(doc, "y"), coordinate(doc, "x"),
                    Kind.PLACE, distanceMeters));
        }
        return result;
    }

    private List<JsonNode> documents(JsonNode root) {
        JsonNode documents = root == null ? null : root.path("documents");
        if (documents == null || !documents.isArray()) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        }
        List<JsonNode> result = new ArrayList<>(documents.size());
        documents.forEach(result::add);
        return result;
    }

    private List<Location> merge(List<Location> addresses, List<Location> places, boolean addressFirst) {
        List<Location> result = new ArrayList<>(MAX_RESULTS);
        Set<String> seen = new HashSet<>();
        int max = Math.max(addresses.size(), places.size());
        for (int i = 0; i < max && result.size() < MAX_RESULTS; i++) {
            if (addressFirst) {
                add(addresses, i, seen, result);
                add(places, i, seen, result);
            } else {
                add(places, i, seen, result);
                add(addresses, i, seen, result);
            }
        }
        return List.copyOf(result);
    }

    private void add(List<Location> source, int index, Set<String> seen, List<Location> result) {
        if (index >= source.size() || result.size() == MAX_RESULTS) {
            return;
        }
        Location location = source.get(index);
        String key = location.name().strip().toLowerCase(Locale.ROOT) + "|" + location.lat() + "|" + location.lng();
        if (seen.add(key)) {
            result.add(location);
        }
    }

    private boolean addressFirst(String query) {
        return query.matches(".*\\d.*")
                || query.matches(".*(?:시|도|군|구|읍|면|동|리|로|길)\\s+.*");
    }

    private LocalSearchException combinedFailure(LocalSearchException first, LocalSearchException second) {
        if (first != null && first.getCode() == StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE
                || second != null && second.getCode() == StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE) {
            return new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_UNAVAILABLE);
        }
        if (first != null && first.getCode() == StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT
                || second != null && second.getCode() == StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT) {
            return new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_TIMEOUT);
        }
        return new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : null;
    }

    private String boundedName(String value) {
        if (value == null || value.length() <= 100) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(99)) ? 99 : 100;
        return value.substring(0, end);
    }

    private String nestedText(JsonNode node, String parent, String field) {
        JsonNode nested = node == null ? null : node.path(parent);
        return nested == null || nested.isMissingNode() || nested.isNull() ? null : nullableText(nested, field);
    }

    private double coordinate(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        }
        try {
            double coordinate = Double.parseDouble(value);
            boolean latitude = "y".equals(field);
            double min = latitude ? -90 : -180;
            double max = latitude ? 90 : 180;
            if (!Double.isFinite(coordinate) || coordinate < min || coordinate > max) {
                throw new NumberFormatException();
            }
            return coordinate;
        } catch (NumberFormatException e) {
            throw new LocalSearchException(StoreCode.LOCATION_SEARCH_PROVIDER_ERROR);
        }
    }
}
