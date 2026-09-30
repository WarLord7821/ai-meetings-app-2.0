package com.ai.meetingnotes.dto;

import com.ai.meetingnotes.entity.User;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    @Builder.Default
    private String type = "Bearer";
    private UserDto user;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserDto {
        private String id;
        private String email;
        private User.PlanTier planTier;
        private User.SubscriptionStatus subscriptionStatus;
        private int summaryCredits;

        public static UserDto from(User user) {
            return UserDto.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .planTier(user.getPlanTier())
                    .subscriptionStatus(user.getSubscriptionStatus())
                    .summaryCredits(user.getSummaryCredits())
                    .build();
        }
    }
}