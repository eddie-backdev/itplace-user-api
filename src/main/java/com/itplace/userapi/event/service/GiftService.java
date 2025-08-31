package com.itplace.userapi.event.service;

import com.itplace.userapi.event.dto.GiftResponse;
import java.util.List;

public interface GiftService {
    List<GiftResponse> getAllGiftNames();
}
