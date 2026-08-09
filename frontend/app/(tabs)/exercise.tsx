import { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated, Alert } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { LinearGradient } from 'expo-linear-gradient';
import { useLocalSearchParams } from 'expo-router';
import { exercisesService } from '@/services/exercisesService';
import { aiService } from '@/services/aiService';
import type { FeedbackTemplate } from '@/types/feedback';
import { FEEDBACK_TYPE_LABEL } from '@/types/feedback';
import type { AiFeedbackType } from '@/types/pose';
import {
  Camera as CameraIcon,
  ChevronLeft,
  Grid3x3,
  Maximize,
  Zap,
  ChevronUp,
  ChevronDown,
  Play,
  FlaskConical,
} from 'lucide-react-native';
import { useRouter } from 'expo-router';
import * as Haptics from 'expo-haptics';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';

/** 색상을 rgba 문자열로 변환 (hex만 지원) */
function hexToRgba(hex: string, alpha: number) {
  const clean = hex.replace('#', '');
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/** 싱크로율 → 색상 (80%+ 정석 / 60~80% 교정 필요 / <60% 부상 위험) */
function getSyncColor(rate: number) {
  if (rate >= 80) return COLORS.syncHigh; // 라임 그린 — 정석
  if (rate >= 60) return COLORS.syncMid;  // 주황 — 교정 필요
  return COLORS.syncLow;                  // 빨강 — 부상 위험
}

/** 백엔드 exerciseId → AI exercise_type 매핑 */
function exerciseTypeOf(exerciseId: number): string {
  // 백엔드 시드: 1=스쿼트, 2=런지, 3=플랭크
  // AI 측은 squat/deadlift/pullup 분석기만 있음. 매핑 부족분은 squat 폴백.
  switch (exerciseId) {
    case 1: return 'squat';
    case 2: return 'lunge';
    case 3: return 'plank';
    default: return 'squat';
  }
}

/** 임시 mock base64 1×1 px JPEG. 카메라 frame capture 도입 시 교체.
 *  실제 분석은 mediapipe 라 0byte 페이로드는 거부될 수 있으나, 폴링 흐름 검증용. */
const MOCK_FRAME_B64 =
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAAAAAAAAAAAAAAAAAAAAAP/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AL+AB//Z';

export default function ExerciseScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ exerciseId?: string }>();
  // 운동 종목 — 라우트 param 우선, 기본 1(스쿼트). 추후 종목 선택 UI 추가 시 정리.
  const exerciseId = params.exerciseId ? Number(params.exerciseId) : 1;

  const [permission, requestPermission] = useCameraPermissions();
  const [isRecording, setIsRecording] = useState(false);
  const [syncRate, setSyncRate] = useState(0);
  const [guideOpen, setGuideOpen] = useState(false);

  // 백엔드 세션 (POST /exercises/sessions → PATCH /sessions/{id}/end)
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  // 페르소나 멘트 프리로드 (TTS 보류 — 일단 받아서 보관만)
  const [, setFeedbackTemplates] = useState<FeedbackTemplate[]>([]);
  // AI 응답 — 최근 자세 결함 라벨 (시각 표시용)
  const [lastFeedback, setLastFeedback] = useState<AiFeedbackType | null>(null);
  // AI 응답 — 누적 횟수
  const [repCount, setRepCount] = useState(0);
  // 카메라 ref (takePictureAsync 호출용)
  const cameraRef = useRef<CameraView | null>(null);

  const handleToggleRecord = async () => {
    if (busy) return;
    setBusy(true);
    try {
      if (!isRecording) {
        // ── 시작: 백엔드에 세션 생성 + 멘트 프리로드 ─────────
        const res = await exercisesService.startSession({ exerciseId });
        setSessionId(res.data.sessionId);
        // 멘트 프리로드는 실패해도 운동은 진행
        exercisesService
          .getFeedbackTemplates(exerciseId)
          .then((tpl) => setFeedbackTemplates(tpl.data))
          .catch(() => {});
        setIsRecording(true);
      } else {
        // ── 종료: 백엔드 종료 통보 + 리포트로 이동 ──────────
        const id = sessionId;
        setIsRecording(false);
        if (id != null) {
          await exercisesService.endSession(id);
          router.replace(`/report/${id}` as any);
        }
      }
    } catch (e: any) {
      console.error('[exercise session] status=', e?.response?.status, 'data=', e?.response?.data);
      Alert.alert(
        '운동 세션 오류',
        e?.response?.data?.message ?? '세션 처리 중 오류가 발생했습니다',
      );
      // 시작 실패면 녹화 상태 유지 안 함 / 종료 실패면 일단 화면은 종료 처리됨
      if (!isRecording) setIsRecording(false);
    } finally {
      setBusy(false);
    }
  };

  // 테두리 점멸 애니메이션
  const borderOpacity = useRef(new Animated.Value(0)).current;

  // 녹화 정지 시 싱크로율 0 + 결함 라벨 + rep 초기화
  useEffect(() => {
    if (!isRecording) {
      setSyncRate(0);
      setLastFeedback(null);
      setRepCount(0);
    }
  }, [isRecording]);

  // 자세 결함 토스트 — 4초 후 자동 사라짐
  useEffect(() => {
    if (!lastFeedback) return;
    const t = setTimeout(() => setLastFeedback(null), 4000);
    return () => clearTimeout(t);
  }, [lastFeedback]);

  // ── AI 폴링 (분기 H2) ────────────────────────────────────
  // takePictureAsync 는 셔터·인코딩 비용이 크므로 10fps 는 비현실적.
  // ~3fps (330ms) 로 시작, 실제 디바이스에서 부담되면 더 줄여도 됨.
  // AI_PUBLIC_TOKEN 미설정 시 폴링 비활성 (DEV 시연 모드 — DEV 패널만 동작)
  // 내부 gRPC 토큰과 값이 분리돼 있다 (이슈 #134) — aiService.ts 주석 참고
  useEffect(() => {
    if (!isRecording || sessionId == null) return;
    const token = process.env.EXPO_PUBLIC_AI_PUBLIC_TOKEN;
    if (!token) return;

    let cancelled = false;
    const exerciseType = exerciseTypeOf(exerciseId);
    const intervalMs = 330; // ~3fps — takePictureAsync 부담 고려

    const tick = async () => {
      if (cancelled) return;
      let image = MOCK_FRAME_B64;
      // 실제 카메라 프레임 (가능하면). 카메라가 아직 마운트 안 됐을 때만 mock 사용.
      try {
        const cam = cameraRef.current;
        if (cam) {
          const snap = await cam.takePictureAsync({
            base64: true,
            quality: 0.4, // 분석엔 충분, 페이로드 줄임
            skipProcessing: true, // 회전·EXIF 처리 스킵 → 지연 단축
            shutterSound: false,
          });
          if (snap?.base64) image = snap.base64;
        }
      } catch (e) {
        if (__DEV__) {
          // eslint-disable-next-line no-console
          console.warn('[camera capture]', (e as Error).message);
        }
      }

      if (cancelled) return;
      try {
        const res = await aiService.detectPose({
          image,
          exercise_type: exerciseType,
          session_id: sessionId,
          timestamp_sec: Date.now() / 1000,
        });
        if (cancelled) return;
        const r = res.data;
        if (r.sync_rate != null) setSyncRate(Math.round(r.sync_rate));
        if (r.feedback_type) setLastFeedback(r.feedback_type);
        if (r.rep_count != null) setRepCount(r.rep_count);
      } catch (e: any) {
        if (__DEV__) {
          // eslint-disable-next-line no-console
          console.warn('[ai pose] status=', e?.response?.status, e?.message);
        }
      }
    };

    const id = setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [isRecording, sessionId, exerciseId]);

  // 싱크로율 낮을 때 진동 피드백
  const prevSyncRef = useRef(syncRate);
  useEffect(() => {
    if (!isRecording) return;
    const prev = prevSyncRef.current;
    prevSyncRef.current = syncRate;

    // 부상 위험 구간 (<60%) 진입/유지 시 진동 알림
    if (syncRate < 60 && prev >= 60) {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } else if (syncRate < 60) {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }
  }, [syncRate, isRecording]);

  // 싱크로율에 따른 비네트 페이드 애니메이션
  useEffect(() => {
    if (!isRecording) {
      borderOpacity.setValue(0);
      return;
    }

    if (syncRate < 60) {
      // 부상 위험 구간(<60%): 빨강이 부드럽게 페이드 in/out
      const pulse = Animated.loop(
        Animated.sequence([
          Animated.timing(borderOpacity, { toValue: 0.9, duration: 800, useNativeDriver: false }),
          Animated.timing(borderOpacity, { toValue: 0.4, duration: 800, useNativeDriver: false }),
        ]),
      );
      pulse.start();
      return () => pulse.stop();
    }

    // 60% 이상: 은은하게 고정 (정석 80%+ 는 더 옅게)
    Animated.timing(borderOpacity, {
      toValue: syncRate >= 80 ? 0.5 : 0.6,
      duration: 400,
      useNativeDriver: false,
    }).start();
  }, [syncRate, isRecording]);

  const syncColor = getSyncColor(syncRate);

  // 카메라 권한 없을 때
  if (!permission) {
    return (
      <View style={styles.center}>
        <Text style={styles.permText}>카메라 연결 중...</Text>
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.center}>
        <CameraIcon size={48} color={COLORS.textMuted} strokeWidth={1.75} />
        <Text style={styles.permTitle}>카메라 권한이 필요합니다</Text>
        <Text style={styles.permDesc}>
          실시간 자세 분석을 위해 카메라 접근을 허용해주세요.
        </Text>
        <Button
          title="카메라 허용"
          onPress={requestPermission}
          style={{ marginTop: SPACING.xl }}
        />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* 싱크로율 기반 비네트 그라데이션 (4방향) */}
      <Animated.View
        style={[styles.vignetteWrap, { opacity: borderOpacity }]}
        pointerEvents="none"
      >
        <LinearGradient
          colors={[hexToRgba(syncColor, 0.85), 'transparent']}
          style={styles.vignetteTop}
        />
      </Animated.View>

      {/* 카메라 뷰 */}
      <CameraView ref={cameraRef} style={styles.camera} facing="front">
        {/* 상단 UI 오버레이 */}
        <View style={styles.topOverlay}>
          <TouchableOpacity onPress={() => router.back()} style={styles.iconBtn}>
            <ChevronLeft size={20} color={COLORS.text} strokeWidth={2} />
          </TouchableOpacity>
          <View style={styles.topRight}>
            <TouchableOpacity style={styles.iconBtn}>
              <Grid3x3 size={18} color={COLORS.text} strokeWidth={2} />
            </TouchableOpacity>
            <TouchableOpacity style={styles.iconBtn}>
              <Maximize size={18} color={COLORS.text} strokeWidth={2} />
            </TouchableOpacity>
          </View>
        </View>

        {/* 싱크로율 게이지 */}
        <View style={styles.syncGauge}>
          <Zap size={14} color={syncColor} strokeWidth={2} fill={syncColor} />
          <Text style={styles.syncLabel}>싱크로율</Text>
          <View style={styles.syncBarBg}>
            <View
              style={[
                styles.syncBarFill,
                { width: `${syncRate}%`, backgroundColor: syncColor },
              ]}
            />
          </View>
          <Text style={[styles.syncValue, { color: syncColor }]}>{syncRate}%</Text>
        </View>

        {/* 자세 결함 토스트 (AI feedback_type 응답) */}
        {lastFeedback && (
          <View style={styles.feedbackToast}>
            <Zap size={14} color={COLORS.warning} strokeWidth={2} fill={COLORS.warning} />
            <Text style={styles.feedbackToastText}>
              {FEEDBACK_TYPE_LABEL[lastFeedback] ?? lastFeedback}
            </Text>
          </View>
        )}

        {/* 운동 횟수 카운터 (AI rep_count 응답) */}
        {isRecording && (
          <View style={styles.repCounter}>
            <Text style={styles.repValue}>{repCount}</Text>
            <Text style={styles.repLabel}>회</Text>
          </View>
        )}

        {/* 촬영 가이드 (접기/펴기) */}
        <TouchableOpacity
          style={styles.guideToggle}
          onPress={() => setGuideOpen(!guideOpen)}
          activeOpacity={0.7}
        >
          <Text style={styles.guideToggleText}>촬영 가이드</Text>
          {guideOpen ? (
            <ChevronUp size={14} color={COLORS.textSecondary} strokeWidth={2} />
          ) : (
            <ChevronDown size={14} color={COLORS.textSecondary} strokeWidth={2} />
          )}
        </TouchableOpacity>
        {guideOpen && (
          <View style={styles.guidePanel}>
            <Text style={styles.guideRow}>📐  정면 또는 측면(45°)에서 촬영</Text>
            <Text style={styles.guideRow}>🧍  전신이 보이도록 1.5m 이상 거리 확보</Text>
            <Text style={styles.guideRow}>💡  밝은 조명, 단색 배경 권장</Text>
            <Text style={styles.guideRow}>🚫  거울 반사, 여러 사람 환경 주의</Text>
          </View>
        )}

        {/* DEV: 싱크로율 수동 조절 (AI 서버 연동 전 테스트용) */}
        {__DEV__ && isRecording && (
          <View style={styles.devPanel}>
            <View style={styles.devLabelRow}>
              <FlaskConical size={14} color={COLORS.textSecondary} strokeWidth={2} />
              <Text style={styles.devLabel}>DEV 싱크로율 테스트</Text>
            </View>
            <Text style={styles.devGuide}>
              <Text style={{ color: COLORS.syncHigh, fontWeight: '700' }}>80%↑ 정석</Text>
              <Text>  ·  </Text>
              <Text style={{ color: COLORS.syncMid, fontWeight: '700' }}>60~80% 교정 필요</Text>
              <Text>  ·  </Text>
              <Text style={{ color: COLORS.syncLow, fontWeight: '700' }}>60%↓ 부상 위험</Text>
            </Text>
            <View style={styles.devRow}>
              <TouchableOpacity
                style={[styles.devBtn, { borderColor: COLORS.syncLow }]}
                onPress={() => setSyncRate(40)}
              >
                <Text style={[styles.devBtnText, { color: COLORS.syncLow }]}>
                  40%
                </Text>
                <Text style={styles.devBtnSub}>부상 위험</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.devBtn, { borderColor: COLORS.syncMid }]}
                onPress={() => setSyncRate(70)}
              >
                <Text style={[styles.devBtnText, { color: COLORS.syncMid }]}>
                  70%
                </Text>
                <Text style={styles.devBtnSub}>교정 필요</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.devBtn, { borderColor: COLORS.syncHigh }]}
                onPress={() => setSyncRate(90)}
              >
                <Text style={[styles.devBtnText, { color: COLORS.syncHigh }]}>
                  90%
                </Text>
                <Text style={styles.devBtnSub}>정석</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

        {/* 하단: 기준영상 + 녹화 버튼 */}
        <View style={styles.bottomOverlay}>
          <View style={styles.bottomCenter}>
            <Text style={styles.recordHint}>
              {isRecording ? '운동 중...' : '버튼을 눌러 녹화를 시작하세요'}
            </Text>
            <TouchableOpacity
              style={[
                styles.recordBtn,
                isRecording && styles.recordBtnActive,
                busy && { opacity: 0.6 },
              ]}
              onPress={handleToggleRecord}
              disabled={busy}
              activeOpacity={0.7}
            >
              <View
                style={[styles.recordDot, isRecording && styles.recordDotActive]}
              />
            </TouchableOpacity>
          </View>

          {/* 기준 영상 미리보기 */}
          <TouchableOpacity style={styles.refVideoBtn} activeOpacity={0.7}>
            <Play size={14} color={COLORS.primary} strokeWidth={2} fill={COLORS.primary} />
            <Text style={styles.refVideoText}>기준 영상</Text>
          </TouchableOpacity>
        </View>
      </CameraView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.black },
  center: {
    flex: 1,
    backgroundColor: COLORS.background,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: SPACING.xxl,
  },
  permText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.md },
  permTitle: {
    color: COLORS.text,
    fontSize: FONT_SIZE.lg,
    fontWeight: '700',
    marginTop: SPACING.xl,
  },
  permDesc: {
    color: COLORS.textSecondary,
    fontSize: FONT_SIZE.sm,
    textAlign: 'center',
    marginTop: SPACING.sm,
  },

  // 비네트 그라데이션
  vignetteWrap: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 10,
  },
  vignetteTop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: 260,
  },

  camera: { flex: 1 },

  // Top overlay
  topOverlay: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingTop: 50,
    paddingHorizontal: SPACING.lg,
  },
  topRight: { flexDirection: 'row', gap: SPACING.md },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.4)',
    justifyContent: 'center',
    alignItems: 'center',
  },

  // Sync gauge
  syncGauge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.lg,
    borderRadius: RADIUS.sm,
    paddingHorizontal: SPACING.md,
    paddingVertical: SPACING.sm,
    gap: SPACING.sm,
  },
  syncLabel: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs },
  syncBarBg: {
    flex: 1,
    height: 6,
    backgroundColor: COLORS.surfaceLight,
    borderRadius: 3,
  },
  syncBarFill: {
    height: 6,
    borderRadius: 3,
  },
  syncValue: {
    fontSize: FONT_SIZE.md,
    fontWeight: '800',
    minWidth: 40,
    textAlign: 'right',
  },

  // 자세 결함 토스트
  feedbackToast: {
    position: 'absolute',
    top: 130,
    left: 0,
    right: 0,
    marginHorizontal: 32,
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    backgroundColor: 'rgba(0,0,0,0.72)',
    borderRadius: RADIUS.full,
    borderWidth: 1,
    borderColor: COLORS.warning,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
  },
  feedbackToastText: {
    color: COLORS.text,
    fontSize: FONT_SIZE.md,
    fontWeight: '700',
  },

  // 운동 횟수 카운터
  repCounter: {
    position: 'absolute',
    top: '40%',
    right: SPACING.xxl,
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: SPACING.lg,
    paddingVertical: SPACING.md,
    borderRadius: RADIUS.lg,
    minWidth: 72,
  },
  repValue: {
    fontSize: 40,
    fontWeight: '900',
    color: COLORS.primary,
    lineHeight: 44,
  },
  repLabel: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
    fontWeight: '600',
  },

  // Bottom overlay
  bottomOverlay: {
    position: 'absolute',
    bottom: 30,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'center',
    paddingHorizontal: SPACING.xxl,
  },
  bottomCenter: { alignItems: 'center', flex: 1 },
  recordHint: {
    color: COLORS.textSecondary,
    fontSize: FONT_SIZE.sm,
    marginBottom: SPACING.md,
  },
  recordBtn: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 4,
    borderColor: COLORS.white,
    justifyContent: 'center',
    alignItems: 'center',
  },
  recordBtnActive: { borderColor: COLORS.error },
  recordDot: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: COLORS.error,
  },
  recordDotActive: {
    width: 28,
    height: 28,
    borderRadius: 6,
  },

  refVideoBtn: {
    position: 'absolute',
    right: SPACING.xxl,
    bottom: 10,
    alignItems: 'center',
    gap: 4,
  },
  refVideoText: { color: COLORS.textSecondary, fontSize: FONT_SIZE.xs },

  // 촬영 가이드
  guideToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACING.sm,
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.sm,
    borderRadius: RADIUS.sm,
    paddingVertical: SPACING.sm,
  },
  guideToggleText: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
  },
  guidePanel: {
    backgroundColor: 'rgba(0,0,0,0.6)',
    marginHorizontal: SPACING.lg,
    marginTop: 4,
    borderRadius: RADIUS.sm,
    padding: SPACING.md,
    gap: SPACING.xs,
  },
  guideRow: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },

  // DEV 패널
  devPanel: {
    backgroundColor: 'rgba(0,0,0,0.7)',
    marginHorizontal: SPACING.lg,
    marginTop: SPACING.sm,
    borderRadius: RADIUS.sm,
    padding: SPACING.md,
    borderWidth: 1,
    borderColor: COLORS.warning,
  },
  devLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    marginBottom: SPACING.sm,
  },
  devLabel: {
    fontSize: FONT_SIZE.xs,
    color: COLORS.textSecondary,
    fontWeight: '700',
  },
  devGuide: {
    fontSize: 10,
    color: COLORS.textMuted,
    textAlign: 'center',
    marginBottom: SPACING.sm,
  },
  devRow: {
    flexDirection: 'row',
    gap: SPACING.sm,
  },
  devBtn: {
    flex: 1,
    borderWidth: 1,
    borderRadius: RADIUS.sm,
    paddingVertical: SPACING.sm,
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.4)',
  },
  devBtnText: {
    fontSize: FONT_SIZE.md,
    fontWeight: '800',
  },
  devBtnSub: {
    fontSize: 10,
    color: COLORS.textMuted,
    marginTop: 2,
  },
});
