package com.shadowfit.dto.login;

import com.shadowfit.model.member.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
//로그인 시 활용
public class CustomUserInfoDto{
    private String userId;
    private UserRole role;
}
