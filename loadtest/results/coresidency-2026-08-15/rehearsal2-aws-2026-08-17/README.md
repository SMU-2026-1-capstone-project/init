# P6 2라운드 rig — EC2 리허설 2차 (2026-08-17)

rig: [`../README.md`](../README.md) · 1차: [`../rehearsal-aws-2026-08-17/README.md`](../rehearsal-aws-2026-08-17/README.md) ·
설계: [`../../../../docs/decisions/ai-coresidency-capacity.md`](../../../../docs/decisions/ai-coresidency-capacity.md) §11

> ⚠️ **측정값이 아니다.** 20초 판·5·10세션짜리 축소 리허설이고, **경로가 서는지만** 본다.
> 여기 숫자를 용량으로 인용하지 말 것. 특히 §T 의 `+0.3%` 는 아래 §3 을 읽지 않고 인용하면 틀린다.

**무대**: 대상 `c7i.4xlarge`(172.31.44.18) + 부하기 `c7i.xlarge`(4 vCPU) · ap-northeast-2c ·
AL2023 `ami-00f6db7984ad32b20` · 커밋 `9ca32aa` 의 rig 3파일을 scp 로 얹어서 돌렸다.
**완주**했다 — 8판 + §T 2판, 스윕 549초. 인스턴스는 둘 다 terminate(인스턴스 0 · 볼륨 0 확인).

---

## 1. 이번 라운드의 결론

**1차에서 «못 본 것» 으로 남았던 넷이 전부 섰다.** 지금까지 두 라운드 동안 한 번도 실행
검증된 적 없던 계측 장치들이다.

| 항목 | 1차 | 2차 |
|---|---|---|
| ⑧ 자기 프로브 | ❌ `-n` 결함으로 스윕이 스스로 껐다 | ✅ `probe_rtt.tsv` **8판 전부 실데이터** |
| ⑩ §T 코어 팔 | ❌ 격자 뒤에 붙는데 그 전에 중단 | ✅ `lc2_t1` · `lcF_t1` 2판 · `loader_cpus` = `2`/`full` |
| `cores_assert_*` 게이트 | ❌ 스윕이 끝까지 안 가 판정 미도달 | ✅ **6종 전부 통과** |
| preflight 단계 | ❌ S3 로 통째 건너뜀 | ❌ **여전히 건너뜀** ([#264](https://github.com/Shadowfit/init/issues/264)) |

```
✅ 8판 전부 성립 (setup_fail 0)
✅ 從 부하 4판 전부 성립 (성공 응답 > 0)
✅ 옆 지표 수집 성립 (mid 게이지 8점)
✅ 부하기 계측 성립                                    ← cores_assert_loader (⑦)
✅ 두 시계 성립 (8판)                                   ← cores_assert_probe  (⑧)
✅ §T 성립 — 제한 30.2 fps ↔ 전체 30.1 fps (차 +0.3%)   ← cores_assert_taskset (⑩)
🔴 최종 업로드 실패                                     ← S3 (#264)
```

**즉 이 라운드가 답한 것은 「이제 정식 라운드를 돌려도 된다」 하나다. 아직 아무것도 재지 않았다.**

## 2. 캡 전이가 처음으로 표에 남았다

팔 순서를 `C A B` 로 둬서 **캡이 걸린 뒤 풀리는 전이**를 지나갔다(`caps.tsv`).

| 팔 | ai | backend | mysql | 기대 | 판정 |
|---|---|---|---|---|---|
| C | 8000000000 | 4000000000 | 4000000000 | 8·4·4 | `OK` |
| A | 0 | 0 | 0 | — | `OK` |
| B | 0 | 0 | 0 | — | `OK` |

1차의 발견(「`STALE` 이 한 번도 안 난다 = compose 가 override 를 지우면 컨테이너를 실제로
재생성한다」)이 **캡 → 해제 방향에서도** 유지된다.

## 3. 🔴 §T 의 `+0.3%` 를 「부하기는 병목이 아니다」로 읽으면 안 된다

게이트는 통과했지만, **그 판에서 부하기가 거의 놀고 있었다.** `loader_B_lc2_t1_10.tsv`:

```
epoch        cpu_pct  load_ai_pct  ghz_pct  load1
1786940235   6.4      2.0          2.8      0.03
1786940240   6.4      1.6          2.2      0.03
1786940245   5.6      1.8          3.0      0.03
```

부하기 총 CPU **5~17%**, `load_ai_pct` **1.6~2.4%**. 4 vCPU 중 사실상 한 코어도 안 쓴다.
**노는 기계를 2코어로 묶어도 안 느려지는 것은 당연하다.**

→ 참인 것은 **«§T 장치가 정상 작동한다»** 까지다. [#250](https://github.com/Shadowfit/init/issues/250) 의
질문(1라운드 천장이 서버 탓이냐 부하기 탓이냐)은 **레벨 120·160 에서 같은 쌍을 돌려야** 답이 나온다.
`CORES_TASKSET_LEVELS` 기본값이 `"120 160"` 인 이유가 그것이다 — 리허설은 `10` 으로 줄여 돈다.

## 4. 이번에 잡은 것

### 4-1. 🔴 §11 의 2차 리허설 명령으로는 §T 가 안 돈다 → [#260](https://github.com/Shadowfit/init/issues/260)

문서 명령이 `CORES_ARMS="C A"` 인데 `TASKSET_ARM` 기본값은 `B` 다. `run_taskset_block` 은
팔이 `ARMS` 에 없으면 **경고 한 줄 남기고 건너뛴다**(`../coresidency_sweep.sh:899`).
**⑩ 검증이 목적인 명령에서 ⑩ 이 빠진다.** `"C A B"` 로 고쳐서 태웠고, 그래서 §T 가 돌았다.

### 4-2. 🔴 두 토큰을 같게 넣으면 AI 가 안 뜬다 → [#261](https://github.com/Shadowfit/init/issues/261)

§11 명령의 `GHZ_TOKEN=<같음>` 을 「`AI_PUBLIC_TOKEN` 과 같은 값」으로 읽어 그렇게 넣었더니
`shadowfit-ai` 가 크래시 루프에 빠졌다. 앱이 하드 실패로 막는다(#230 — 번들 배포 토큰으로
내부 gRPC 까지 뚫린 #134 사고 때문에 나눈 값). `GHZ_TOKEN` 이 받아야 하는 것은 `INTERNAL_API_TOKEN` 이다.

### 4-3. 🔴 그때 부트스트랩은 `BOOTSTRAP_RC=0` 이었다 → [#265](https://github.com/Shadowfit/init/issues/265)

측정 대상 세 컨테이너 중 하나가 크래시 루프인데 성공으로 끝났다. 헬스체크가 `die` 가 아니라
경고다(`../../../aws/bootstrap.sh:299~315`). 사람이 `docker ps` 를 눈으로 봐서 잡았다.

### 4-4. ✅ `docker update --cpus` 는 문다 — 그런데 해제가 안 된다 → [#263](https://github.com/Shadowfit/init/issues/263)

§11 ②의 선행 확인. cgroup `cpu.max` 가 실제로 바뀐다(`--cpus 2` → `200000 100000`).
**그러나 `--cpus 0` 으로는 안 풀리고**(`--cpu-quota -1` 이어야 한다), 풀린 뒤에도
`docker inspect` 의 `NanoCpus` 는 옛 값을 남긴다. `assert_caps` 가 읽는 값이 그것이라
**② 와 ③ 은 지금 형태로 같이 못 간다.**

### 4-5. ⚠️ rate 경고가 6판 중 3판에서 떴다 — 요청 하나 차이다 → [#259](https://github.com/Shadowfit/init/issues/259)

30초 창에 569요청 = 18.97 rps, 목표 19. `fail` 은 8판 전부 0 이다. 창 경계의 반올림인데
비교가 허용오차 없는 `<` 라 운다(`../coresidency_sweep.sh:393`). 정식 91판이면 40~50번 운다.

### 4-6. ✅ CRLF 함정의 진짜 원인은 scp 가 아니다

1차 메모는 「Windows 에서 scp 로 얹으면 CRLF」라고 적었지만, repo 블롭은 **LF** 다.
바꾸는 것은 작업 트리 쪽의 `core.autocrlf=true` 다. `git show HEAD:<path>` 로 블롭을 직접
뽑으면 **원격 `sed -i 's/\r$//'` 자체가 불필요**하다. 이번에 그렇게 얹어서 CR 0바이트를 확인했다.

## 5. ⚠️ 미검증 — `rx_mbps` 가 전 샘플 0.00 이다

`loader_*.tsv` 의 네트워크 열이 `rx_mbps=0.00` · `tx_mbps=0.01` 로 고정이다. 초당 19요청 +
30fps 프레임을 보내는 조건에서 1.25 KB/s 는 낮아 보인다 — 인터페이스 선택이나 델타 계산을
의심할 만하다. **다만 페이로드 크기를 확인하지 않았으므로 결함으로 단정하지 않는다.**
정식 라운드에서 같은 값이 나오면 그때 이슈로 올린다.

## 6. 원문

`coresidency.tsv`(8행 + `loader_cpus` 열) · `ghz.tsv`(p50/p95/p99) · `side.tsv` ·
`caps.tsv`(12행) · **`probe_rtt.tsv`(8행 — 1차엔 헤더뿐이었다)** · `loader_*.tsv`(8판) ·
`probe_req_*.tsv` · `req_*.tsv` · `stats_*.tsv` · `run_all.log`.

⚠️ `ghz_*.json` 원본은 **안 받았다** — 리허설이라 값이 필요 없다.
⚠️ S3 업로드는 실패했다([#264](https://github.com/Shadowfit/init/issues/264)). 회수 경로는 scp 였다.
