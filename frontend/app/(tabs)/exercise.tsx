import { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Animated } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import FontAwesome from '@expo/vector-icons/FontAwesome';
import { useRouter } from 'expo-router';
import * as Haptics from 'expo-haptics';
import { COLORS, FONT_SIZE, SPACING, RADIUS } from '@/constants/Colors';
import Button from '@/components/ui/Button';

/** 싱크로율 → 색상 */
function getSyncColor(rate: number) {
  if (rate >= 70) return COLORS.syncHigh; // 라임 그린
  if (rate >= 40) return COLORS.syncMid;  // 주황
  return COLORS.syncLow;                  // 빨강
}

export default function ExerciseScreen() {
  const router = useRouter();
  const [permission, requestPermission] = useCameraPermissions();
  const [isRecording, setIsRecording] = useState(false);
  const [syncRate, setSyncRate] = useState(0);

  // 테두리 점멸 애니메이션
  const borderOpacity = useRef(new Animated.Value(0)).current;

  // 녹화 중 mock 싱크로율 시뮬레이션 (TODO: AI 서버 연동 시 제거)
  useEffect(() => {
    if (!isRecording) {
      setSyncRate(0);
      return;
    }
    const interval = setInterval(() => {
      setSyncRate(Math.floor(Math.random() * 100));
    }, 1500);
    return () => clearInterval(interval);
  }, [isRecording]);

  // 싱크로율 낮을 때 진동 피드백
  const prevSyncRef = useRef(syncRate);
  useEffect(() => {
    if (!isRecording) return;
    const prev = prevSyncRef.current;
    prevSyncRef.current = syncRate;

    if (syncRate < 40 && prev >= 40) {
      // 낮은 구간 진입 시 강한 진동
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } else if (syncRate < 40) {
      // 낮은 구간 유지 시 가벼운 진동
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    }
  }, [syncRate, isRecording]);

  // 싱크로율 낮을 때 테두리 점멸
  useEffect(() => {
    if (!isRecording) {
      borderOpacity.setValue(0);
      return;
    }

    if (syncRate < 40) {
      // 빨간 테두리 점멸
      const pulse = Animated.loop(
        Animated.sequence([
          Animated.timing(borderOpacity, { toValue: 1, duration: 300, useNativeDriver: false }),
          Animated.timing(borderOpacity, { toValue: 0.3, duration: 300, useNativeDriver: false }),
        ]),
      );
      pulse.start();
      return () => pulse.stop();
    }

    // 40% 이상이면 고정 표시
    borderOpacity.setValue(isRecording ? 1 : 0);
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
        <FontAwesome name="camera" size={48} color={COLORS.textMuted} />
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
      {/* 싱크로율 기반 테두리 */}
      <Animated.View
        style={[
          styles.cameraBorder,
          {
            borderColor: syncColor,
            opacity: borderOpacity,
          },
        ]}
        pointerEvents="none"
      />

      {/* 카메라 뷰 */}
      <CameraView style={styles.camera} facing="front">
        {/* 상단 UI 오버레이 */}
        <View style={styles.topOverlay}>
          <TouchableOpacity onPress={() => router.back()} style={styles.iconBtn}>
            <FontAwesome name="chevron-left" size={18} color={COLORS.text} />
          </TouchableOpacity>
          <View style={styles.topRight}>
            <TouchableOpacity style={styles.iconBtn}>
              <FontAwesome name="th" size={18} color={COLORS.text} />
            </TouchableOpacity>
            <TouchableOpacity style={styles.iconBtn}>
              <FontAwesome name="expand" size={18} color={COLORS.text} />
            </TouchableOpacity>
          </View>
        </View>

        {/* 싱크로율 게이지 */}
        <View style={styles.syncGauge}>
          <FontAwesome name="bolt" size={14} color={syncColor} />
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

        {/* 하단: 기준영상 + 녹화 버튼 */}
        <View style={styles.bottomOverlay}>
          <View style={styles.bottomCenter}>
            <Text style={styles.recordHint}>
              {isRecording ? '운동 중...' : '버튼을 눌러 녹화를 시작하세요'}
            </Text>
            <TouchableOpacity
              style={[styles.recordBtn, isRecording && styles.recordBtnActive]}
              onPress={() => setIsRecording(!isRecording)}
              activeOpacity={0.7}
            >
              <View
                style={[styles.recordDot, isRecording && styles.recordDotActive]}
              />
            </TouchableOpacity>
          </View>

          {/* 기준 영상 미리보기 */}
          <TouchableOpacity style={styles.refVideoBtn} activeOpacity={0.7}>
            <FontAwesome name="play" size={16} color={COLORS.primary} />
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

  // 싱크로율 테두리
  cameraBorder: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderWidth: 4,
    borderRadius: 0,
    zIndex: 10,
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
});
