# HTTP 읽기 스파이크 — 생성 표 (판정은 README.md 에)

대상 `http://172.31.19.55:8080` · 베이스라인×60·스파이크×3600(20s)·회복 관찰 90s · 4판(0 버림)

| 엔드포인트 | 단계 | p50 (med) | p99 | 유효 판수 |
|---|---|--:|--:|--:|
| t_report_session | baseline | 7.8 | 9.9 | 3 |
| t_report_session | rampup | 5.5 | 11.6 | 3 |
| t_report_session | spike | 5.7 | 7.6 | 3 |
| t_report_session | rampdown | 5.4 | 7.6 | 3 |
| t_report_session | recovery | 7.9 | 10.0 | 3 |
| t_weekly_summary | baseline | 4.2 | 6.8 | 3 |
| t_weekly_summary | rampup | 2.7 | 5.6 | 3 |
| t_weekly_summary | spike | 2.8 | 3.7 | 3 |
| t_weekly_summary | rampdown | 2.7 | 5.1 | 3 |
| t_weekly_summary | recovery | 4.2 | 7.0 | 3 |
| t_calendar | baseline | 3.1 | 5.1 | 3 |
| t_calendar | rampup | 2.2 | 4.3 | 3 |
| t_calendar | spike | 2.2 | 2.8 | 3 |
| t_calendar | rampdown | 2.2 | 3.9 | 3 |
| t_calendar | recovery | 3.0 | 5.1 | 3 |
| t_daily | baseline | 2.5 | 4.4 | 3 |
| t_daily | rampup | 1.9 | 3.4 | 3 |
| t_daily | spike | 1.9 | 2.4 | 3 |
| t_daily | rampdown | 1.9 | 3.6 | 3 |
| t_daily | recovery | 2.6 | 4.6 | 3 |

> 원자료: `raw.tsv` · k6 로그: `logs/`
