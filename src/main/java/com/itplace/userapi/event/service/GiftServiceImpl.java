package com.itplace.userapi.event.service;

import com.itplace.userapi.event.dto.GiftResponse;
import com.itplace.userapi.event.repository.GiftRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GiftServiceImpl implements GiftService {

    private final GiftRepository giftRepository;

    @Override
    public List<GiftResponse> getAllGiftNames() {
        return giftRepository.findAll().stream()
                .map(gift -> new GiftResponse(gift.getGiftName()))
                .toList();
    }
}
