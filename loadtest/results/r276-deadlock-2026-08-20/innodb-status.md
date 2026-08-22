# #276 후속 — 데드락 한 쌍의 잠금 (로컬, 2026-08-20)

`same_partition` 팔 1판(워커 8 · 문 40 · 행 25)에서 데드락 **115 건** 발생.
아래는 `SHOW ENGINE INNODB STATUS` 의 LATEST DETECTED DEADLOCK 원문이다.

```
LATEST DETECTED DEADLOCK
------------------------
2026-08-20 20:29:26 140260827829824
*** (1) TRANSACTION:
TRANSACTION 388216, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 6 lock struct(s), heap size 1128, 5 row lock(s)
MySQL thread id 494, OS thread handle 140258601096768, query id 24499 localhost root update
INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES (904,0,0.000,'{"k":0}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,0.500,'{"k":1}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,1.000,'{"k":2}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,1.500,'{"k":3}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,2.000,'{"k":4}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,2.500,'{"k":5}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,3.000,'{"k":6}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,3.500,'{"k":7}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,4.000,'{"k":8}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,4.500,'{"k":9}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,5.000,'{"k":10}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,5.500,'{"k":11}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,6.000,'{"k":12}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,6.500,'{"k":13}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,7.000,'{"k":14}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,7.500,'{"k":15}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,8.000,'{"k":16}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,8.500,'{"k":17}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,9.000,'{"k":18}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,9.500,'{"k":19}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,10.000,'{"k":20}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,10.500,'{"k":21}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,11.000,'{"k":22}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,11.500,'{"k":23}',45.0,0.0,'','2026-05-28 10:00:00'),(904,0,12.000,'{"k":24}',45.0,0.0,'','2026-05-28 10:00:00') ON DUPLICATE KEY UPDATE session_id = session_id

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 1379 page no 4 n bits 272 index PRIMARY of table `shadowfit`.`pose_data_r276` /* Partition `p2026_05` */ trx id 388216 lock_mode X
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 1379 page no 4 n bits 272 index PRIMARY of table `shadowfit`.`pose_data_r276` /* Partition `p2026_05` */ trx id 388216 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 388217, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 6 lock struct(s), heap size 1128, 5 row lock(s)
MySQL thread id 498, OS thread handle 140258104497728, query id 24500 localhost root update
INSERT INTO pose_data_r276 (session_id,rep_number,timestamp_sec,joint_coordinates,sync_rate,smoothed_knee_angle,feedback_message,created_at) VALUES (901,0,0.000,'{"k":0}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,0.500,'{"k":1}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,1.000,'{"k":2}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,1.500,'{"k":3}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,2.000,'{"k":4}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,2.500,'{"k":5}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,3.000,'{"k":6}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,3.500,'{"k":7}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,4.000,'{"k":8}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,4.500,'{"k":9}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,5.000,'{"k":10}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,5.500,'{"k":11}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,6.000,'{"k":12}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,6.500,'{"k":13}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,7.000,'{"k":14}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,7.500,'{"k":15}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,8.000,'{"k":16}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,8.500,'{"k":17}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,9.000,'{"k":18}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,9.500,'{"k":19}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,10.000,'{"k":20}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,10.500,'{"k":21}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,11.000,'{"k":22}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,11.500,'{"k":23}',45.0,0.0,'','2026-05-28 10:00:00'),(901,0,12.000,'{"k":24}',45.0,0.0,'','2026-05-28 10:00:00') ON DUPLICATE KEY UPDATE session_id = session_id

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 1379 page no 4 n bits 272 index PRIMARY of table `shadowfit`.`pose_data_r276` /* Partition `p2026_05` */ trx id 388217 lock_mode X
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 1379 page no 4 n bits 272 index PRIMARY of table `shadowfit`.`pose_data_r276` /* Partition `p2026_05` */ trx id 388217 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
------------
TRANSACTIONS
```
