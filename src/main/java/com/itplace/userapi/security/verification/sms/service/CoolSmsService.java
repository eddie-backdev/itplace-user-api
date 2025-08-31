package com.itplace.userapi.security.verification.sms.service;

import lombok.extern.slf4j.Slf4j;
import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.response.SingleMessageSentResponse;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CoolSmsService {

    private final DefaultMessageService messageService;
    private final String fromPhone;

    public CoolSmsService(
            @Value("${coolsms.api.key}") String apiKey,
            @Value("${coolsms.api.secret}") String apiSecret,
            @Value("${coolsms.fromPhone}") String fromPhone
    ) {
        this.messageService = NurigoApp.INSTANCE.initialize(apiKey, apiSecret, "https://api.coolsms.co.kr");
        this.fromPhone = fromPhone;
    }

    public void sendMessage(String code, String phoneNumber) {
        Message message = new Message();
        message.setFrom(fromPhone);
        message.setTo(phoneNumber);
        message.setText("[itPlace] 인증번호: " + code + " (3분 이내 유효)");
        SingleMessageSentResponse response = this.messageService.sendOne(new SingleMessageSendingRequest(message));
        log.info("to: {}, from: {}", response.getTo(), response.getFrom());
    }
}
