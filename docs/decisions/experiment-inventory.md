# 실험 재고 — Spring · MySQL · AI 세 파트에서 남은 측정

작성일: 2026-08-17
상태: **살아 있는 목록.** 실험이 닫히면 여기서 지우지 말고 «완료» 로 표시하고 결과를 건다
연관: [`../../loadtest/AWS-RIDE-ALONG.md`](../../loadtest/AWS-RIDE-ALONG.md)(EC2 탑승 목록) ·
[`./db-portfolio-roadmap.md`](./db-portfolio-roadmap.md)(무엇을 왜 만드나) ·
[GitHub 이슈](https://github.com/Shadowfit/init/issues)(개별 결함)

---

## 0. 이 문서가 있는 이유

「다음에 뭘 재야 하나」의 답이 **이슈 40여 건 · 탑승 목록 · 설계 문서 다섯 곳**에 흩어져 있다.
물어볼 때마다 다시 모으고 있었고, 그러다 **이미 등록된 항목을 놓쳤다** — 2026-08-17 에
`c7i.4xlarge`(물리 8코어)를 45분 띄우고도 **R6 를 안 태우고 껐다.** 목록에 없어서가 아니라
목록이 여러 곳에 있어서다.

이 문서는 **재고표**다. 「무엇을 만드나」는 [`db-portfolio-roadmap.md`](./db-portfolio-roadmap.md),
「EC2 를 띄울 때 뭘 같이 태우나」는 [`AWS-RIDE-ALONG.md`](../../loadtest/AWS-RIDE-ALONG.md) 다.
여기는 **「무엇이 아직 안 재졌나」** 만 본다.

🔴 **「측정이 남은 것」과 「구현이 남은 것」은 다른 일이다.** 아래는 **측정** 기준이고,
구현이 선행인 항목은 그렇게 표시했다. 계약·오류 처리·보안 결함은 §4 로 뺐다.

---

## 1. 🗄️ MySQL — 축이 제일 비어 있다

DBA 지원축의 결손 3개(백업 · 복제 · 무중단 DDL) 중 **복제만 아직 0** 이다.
백업은 [P3·P3-b](../../loadtest/results/backup-restore-aws-b-2026-08-13/README.md),
무중단 DDL 은 [P1](../../loadtest/results/online-ddl-aws-2026-08-12/README.md) 이 실측을 갖고 있다.

| 우선 | 실험 | 상태 | 왜 지금 |
|---|---|---|---|
| **1** | **P4 — 복제 지연 · 반동기 대가** | 🟡 [설계 완료](./replication-lag-and-semisync.md) · **rig 없음** | **결손 3축 중 유일한 공백.** ⭐ 반동기 대가는 **AZ 간 RTT 가 지배**하므로 로컬에선 구조적으로 과소평가된다 → **인스턴스 2대 필수.** 리드타임이 길다(rig 부터) |
| **2** | **[#204](https://github.com/Shadowfit/init/issues/204) 리포트 핵심 쿼리** | 미측정 | 파티션 프루닝·커버링·정렬을 **동시에** 놓치고 있다. 읽기축 단일 최대 건 |
| **3** | **[#205](https://github.com/Shadowfit/init/issues/205) 포폴 카드 쿼리 3개** | EXPLAIN 스윕 선행 | 읽기축 결과물이 아직 «후보» 상태다 |
| 4 | **[#219](https://github.com/Shadowfit/init/issues/219) `INSERT IGNORE` 전수 확인** | 미검증 | 「중복만 삼킨다」가 **틀렸다** — FK 위반은 행을 지우고 NOT NULL 위반은 빈 값을 쓴다 |
| 5 | **pose_data 행 모양 × 파티션 상호작용** | 미검증 | DELETE 파편화 자체는 닫혔다(FIFO 누적 없음 · 구멍 뚫기 +24% 계단 1회). 이 **조합**은 안 봤다 |

⚠️ **#204·#205 는 합성 데이터의 한계를 먼저 읽을 것** — 로드테스트 rig 은 단일 템플릿 복제라
**값 분포가 균일**하다. 옵티마이저 카디널리티·generated column 선택도처럼 **분포에 의존하는
실험은 지금 rig 으로 못 한다**(그 사실 자체가 이미 박제돼 있다).

---

## 2. 🌱 Spring — 「측정」보다 「계약이 없다」가 많다

| 우선 | 실험 | 상태 | 왜 지금 |
|---|---|---|---|
| **1** | **H3 — 캡이 «옆» 을 지키는가** | 🟢 **2026-08-17 라운드가 답한다** | 두 라운드가 **판정할 열 자체가 없어** 못 닫았다([#254](https://github.com/Shadowfit/init/issues/254)). 열이 생겼고 2차 리허설에서 실증됐다. H3 이 반증되면 [#212](https://github.com/Shadowfit/init/issues/212)(CPU 캡)의 전제가 흔들린다 |
| **2** | **P5 — 세션 분산도 스윕** (1·2·5·20·100) | 🟢 [설계·rig 완료](./session-spread-sweep.md) | 🔴 **정본 baseline 649.4 RPS 가 «100세션» 값인데 그 100 은 잰 값이 아니다.** 이 앱은 회원당 활성 세션이 1개라 **동시 세션 수 = 동시에 운동 중인 사람 수** — baseline 이 가정한 것보다 분산된 조건일 수 있다 |
| **3** | **P2 — 다운샘플 «1.7배» 다세션 재측정** | 🟡 rig 있음 · 페이로드 재생성 필요 | 🔴 `one-pager.md` 의 **정본 수치인데 조건이 단일 핫세션**이다. **fsync 3.47배를 1.03배로 무너뜨린 바로 그 조건**이고, 「다세션에서 재측정한 적 없다」가 문서에 그대로 붙어 있다 |
| 4 | **[#221](https://github.com/Shadowfit/init/issues/221) `rewriteBatchedStatements` 기여분** | 미측정 | 「효과 없음」은 **JPA 경로 얘기**였고, 지금 그 대가는 확인됐다 |
| 5 | **[#211](https://github.com/Shadowfit/init/issues/211) hibernate `batch_size`** | 미측정 | 엔티티가 전부 IDENTITY 라 **insert batch 가 애초에 꺼져 있다.** 켜져 있다고 믿는 설정이 있다 |
| 6 | **[#207](https://github.com/Shadowfit/init/issues/207) 타임아웃 스윕 메모리** | 미측정 | IN_PROGRESS 세션 **전부**를 매분 메모리에 올린다 — 세션이 안 끝날수록 무거워지는 방향이다 |

---

## 3. 🤖 AI — 가장 큰 미지수가 여기 있다

| 우선 | 실험 | 상태 | 왜 지금 |
|---|---|---|---|
| **1** | **R6 — GIL 이냐 캐시냐** | 🟢 **2026-08-17 라운드 끝에 태운다** | 프레임당 CPU 의 **37%가 «일» 이 아니라 경합**인데 원인 미확정. GIL 가설은 **산술이지 측정이 아니다.** 🔴 **물리 8코어 이상에서만 갈린다** — 로컬 2코어는 구조적으로 불가. **10분** |
| **2** | **R7 — `model_complexity` 0/1/2 배수** | 🟢 같은 박스·같은 자산 | 지금 유일하게 **바로 쓸 수 있는 처리량 레버**(로컬 lite = 추론 −36%). c7i 는 AVX-512·XNNPACK 이 달라 배수가 다를 수 있다. **5분** |
| **3** | **Q5 — 관측 스택 동거 비용** | 미측정 | 2026-08-08 에 관측 스택을 분리한 **이유가 정확히 이건데, 비용을 숫자로 적은 적이 없다.** 팔 D(+19판 · +42분) |
| **4** | 🔴 **라운드 간 절대값 비재현 (+17.7%)** | **원인 가설 없음** | AI CPU 는 869.3%→869.0% 로 **같은데** 처리량만 +17.7%(천장 환산 89.2→105.0세션). **이게 안 풀리면 모든 절대 용량 수치의 신뢰가 흔들린다.** 설계부터 필요 |
| 5 | **[#217](https://github.com/Shadowfit/init/issues/217) 무릎 각도의 z 의존** | 미검증 | 같은 프레임이 **3D 108° · 2D 178°** 로 갈린다 — 정확도 축의 뿌리 |
| 6 | [#234](https://github.com/Shadowfit/init/issues/234) · [#256](https://github.com/Shadowfit/init/issues/256) 정답지 흔들림 → 점수 영향 | 미측정 | **R7 채택의 선행이다** — 처리량 레버의 대가가 정확도라서 |

---

## 4. 측정이 아니라 «구현·계약» 인 것 (별도 축)

여기 것들은 **재는 문제가 아니라 없는 문제**다. 측정 계획에 섞으면 우선순위가 왜곡된다.

| 구분 | 항목 |
|---|---|
| 🔴 **보안 — 측정보다 먼저** | [#187](https://github.com/Shadowfit/init/issues/187) AI HTTP 세션 소유권 검증 부재(번들 토큰만 있으면 **남의 session_id 로 주입**되고 Spring DB 까지 간다) · [#185](https://github.com/Shadowfit/init/issues/185) refresh token 평문 저장 |
| 신뢰성 | [#188](https://github.com/Shadowfit/init/issues/188) 재시도 부재(rep 하나가 통째로 사라진다) · [#206](https://github.com/Shadowfit/init/issues/206) gRPC 예산 미전파 · [#208](https://github.com/Shadowfit/init/issues/208) 종료 정책 3갈래 |
| 계약 | [#209](https://github.com/Shadowfit/init/issues/209) 핸들러 4개 중 1개만 클라이언트 잘못을 `INVALID_ARGUMENT` 로 낸다 · [#218](https://github.com/Shadowfit/init/issues/218) 국면 이름표와 rep 판정이 다른 자를 쓴다 |
| 기능 부재 | [#193](https://github.com/Shadowfit/init/issues/193) · [#228](https://github.com/Shadowfit/init/issues/228) 자세 문제 유형 감지기가 **통째로 없다** |
| 구조 | [#176](https://github.com/Shadowfit/init/issues/176) · [#175](https://github.com/Shadowfit/init/issues/175) · [#174](https://github.com/Shadowfit/init/issues/174) · [#173](https://github.com/Shadowfit/init/issues/173) |

---

## 5. 순서 — 값싼 것과 값비싼 것

1. **2026-08-17 라운드** — H3(Spring 1위) + R6·R7(AI 1·2위)이 **한 번에** 닫힌다.
   R6·R7 은 라운드 끝 **15분**에 AI 축 둘을 닫는다 — 이 목록에서 **단위 시간당 가장 값싸다**
2. **P4 복제** — DBA 결손을 메우는 유일한 항목. rig 부터라 리드타임이 길다. **가장 먼저 착수**할 것
3. **#204 · #205 읽기 쿼리** — 로컬로 되고 포폴 결과물로 직결된다
4. **P5 · P2** — 둘 다 **이미 서류에 나간 정본 수치의 조건을 고치는** 일이다. 우선순위를 낮게 두면 안 된다
5. **절대값 비재현** — 위 전부의 신뢰를 떠받치는 바닥인데 **원인 가설조차 없다.** 설계부터고,
   라운드를 여러 번 돌려야 할 수 있다 — 이 목록에서 **가장 비싸다**

---

## 결정 로그

- **2026-08-17: 문서 신설.** 「다음에 뭘 재나」가 이슈 40여 건 · 탑승 목록 · 설계 문서 다섯 곳에
  흩어져 있어 물을 때마다 다시 모으고 있었다. 직접적 계기는 **같은 날 `c7i.4xlarge` 를 45분
  띄우고도 R6 를 안 태우고 끈 것** — 목록에 없어서가 아니라 목록이 여러 곳에 있어서였다.
  ⚠️ **이 문서는 원본이 아니다.** 개별 결함의 원본은 이슈, 탑승 판단의 원본은
  `AWS-RIDE-ALONG.md` §1 從 표다. 여기 요약이 낡으면 **원본을 열 것**.
