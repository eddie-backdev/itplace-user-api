package com.itplace.userapi.user.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FindEmailResponse {
    String email;
}
