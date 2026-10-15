package com.itplace.userapi.map.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MapStorePreviewPageResponse {
    private List<MapStorePreviewBatchResponse.StorePreview> stores;
    private List<MapStorePreviewBatchResponse.PartnerPreview> partners;
    private boolean hasMore;
    private Long nextCursor;
}
