package com.shadowfit.model.exercise;

/**
 * 운동 자세 피드백 유형. FastAPI 분석기가 감지한 자세 문제 종류를 나타낸다.
 * 새 유형 추가 시 ExerciseFeedbackTemplate 시드 데이터도 함께 추가할 것.
 */
public enum FeedbackType {
    KNEE_OUT,        // 무릎이 발끝보다 나감
    KNEE_IN,         // 무릎이 안쪽으로 모임
    HIP_LOW,         // 엉덩이 처짐
    HIP_HIGH,        // 엉덩이 과도하게 들림
    BACK_BENT,       // 등 굽음
    SHOULDER_TILT,   // 어깨 비대칭
    ELBOW_BENT,      // 팔꿈치 굽음
    HEAD_DOWN        // 고개 숙임
}