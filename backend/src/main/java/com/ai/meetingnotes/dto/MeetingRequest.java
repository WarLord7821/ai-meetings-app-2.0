package com.ai.meetingnotes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingRequest {
    private String title;

    @NotBlank
    @Size(min = 20)
    private String transcript;
}