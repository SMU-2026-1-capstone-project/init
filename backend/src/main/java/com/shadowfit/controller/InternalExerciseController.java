package com.shadowfit.controller;

import com.shadowfit.dto.exercises.PoseDataRequestDto;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.service.Exercise.PoseDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/exercises")
@RequiredArgsConstructor
public class InternalExerciseController {
    private final PoseDataService poseDataService;

    @Value("${internal.api.token}")
    private String internalToken;

    @PostMapping("/pose-data")
    public ResponseEntity<String> receivePoseData(
            @RequestHeader("X-Internal-Token") String token,
            @RequestBody List<PoseDataRequestDto> dtos
    ) {
        if (!internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: Invalid Internal Token");
        }

        // sessionId 별로 그룹핑 후 PoseDataService 호출 (서비스가 sessionId 단위로 처리)
        Map<Long, List<PoseDataRequestDto>> grouped = dtos.stream()
                .collect(Collectors.groupingBy(PoseDataRequestDto::getSessionId));

        for (Map.Entry<Long, List<PoseDataRequestDto>> entry : grouped.entrySet()) {
            List<PoseDataRequest> grpcList = entry.getValue().stream()
                    .map(d -> PoseDataRequest.newBuilder()
                            .setTimestampSec(d.getTimestampSec() != null ? d.getTimestampSec() : 0.0)
                            .setJointCoordinates(d.getJointCoordinates() != null ? d.getJointCoordinates() : "")
                            .build())
                    .toList();
            poseDataService.savePoseDataBatch(entry.getKey(), grpcList);
        }

        return ResponseEntity.ok("Successfully saved " + dtos.size() + " pose data points.");
    }
}