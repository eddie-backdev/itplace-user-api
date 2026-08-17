package com.itplace.userapi.log.advice;

import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.dto.response.MapBenefitDetailResponse;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class BenefitLogAdvice implements ResponseBodyAdvice<Object> {

    private final LogService logService;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getDeclaringClass().getPackageName().contains("benefit");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder
                .currentRequestAttributes()).getRequest();
        String path = req.getRequestURI();
        String param = req.getQueryString();
        Long userId = getUserId();

        if (!isTargetBenefitApi(path)) {
            return body;
        }

        log.info("==== beforeBodyWrite 실행됨 ====");
        if (userId == null) {
            return body;
        }

        String partnerIdStr = req.getParameter("partnerId");
        Long partnerId = null;
        String event = null;
        if (partnerIdStr != null && !partnerIdStr.isBlank()) {
            partnerId = Long.valueOf(partnerIdStr);
            event = "detail";
        }

        if (!(body instanceof ApiResponse<?> topRes)) {
            return body;
        }

        Object data = topRes.getData();
        if (!(data instanceof PageResult<?> dataRes)) {
            log.info("==== saveResonseLog(detail) 저장 ====");
            if (data instanceof MapBenefitDetailResponse detail) {

                log.info("data : {}", data.toString());
                long benefitId = detail.getBenefitId();
                logService.saveResponseLogs(userId, List.of(new ResponseLogCommand(
                        event,
                        benefitId,
                        partnerId,
                        path,
                        param
                )));
            }
            return body;
        }

        Object content = dataRes.getContent();
        String keyword = req.getParameter("keyword");
        if (content instanceof List<?> list && keyword != null && !keyword.isBlank()) {
            List<ResponseLogCommand> commands = list.stream()
                    .filter(BenefitListResponse.class::isInstance)
                    .map(BenefitListResponse.class::cast)
                    .map(benefit -> new ResponseLogCommand(
                            "search",
                            benefit.getBenefitId(),
                            benefit.getPartnerId(),
                            path,
                            param
                    ))
                    .toList();
            if (!commands.isEmpty()) {
                log.info("검색 응답 로그 {}건 저장 요청", commands.size());
                logService.saveResponseLogs(userId, commands);
            }
        }
        return body;
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            log.info("Authentication 정보 없음");
            return null;
        }
        Object principal = auth.getPrincipal();

        if (principal instanceof PrincipalDetails principalDetails) {
            return principalDetails.getUserId();
        }
        log.info("user 정보 없음");
        return null;
    }

    private boolean isTargetBenefitApi(String path) {
        return path.contains("v1/benefit");
    }

}
