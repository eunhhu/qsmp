# 운영자 런북

운영자 확인, 테스트, 복구 명령만 모았습니다.

## 기본 확인

서버가 꺼진 상태에서:

```bash
bash scripts/ci.sh
./server.sh check
./plugins.sh check
./world.sh check
```

`scripts/ci.sh`는 CI와 로컬 공용 smoke test입니다. 서버가 실행 중이면 플러그인
업데이트처럼 서버 중지가 필요한 단계는 건너뛰고, 서버가 꺼진 fresh runner에서는
필요한 Purpur JAR과 플러그인을 준비한 뒤 리소스팩/커스텀 플러그인 빌드와
`server.sh check`까지 확인합니다.

현재 확인된 상태:

- Java 25 OK
- 서버 JAR 있음
- EULA 동의됨
- 서버 프로세스 상태는 `./server.sh check` 기준으로 running/stopped 확인
- 관리 플러그인 해시 OK
- 관리 데이터팩 10개 injected

## 실제 기동 확인

서버 시작:

```bash
./server.sh start
```

콘솔에서:

```text
datapack list enabled
frontier status
version QSMPFrontier
survivor
legacy
tcp list
```

기대:

- `datapack list enabled`: Terralith, Terratonic, Continents, Dungeons and Taverns, Explorify, Structory, Nullscape, True Ending, True Ending 26 Compat, QSMP Rules 표시
- `frontier status`: 전장 상태와 거점 수 표시
- `version QSMPFrontier`: `1.4.0` 및 cinematic raids/dragon raids 포함 설명 표시
- `survivor`: 플레이어만 실행 가능. 콘솔에서는 `Players only.`가 정상
- `legacy`: 플레이어만 실행 가능. 콘솔에서는 `Players only.`가 정상
- `tcp list`: 발견된 챔버 목록 표시

플레이어 GUI QA:

- `QSMP Codex` 웅크린 우클릭: `Battle Readiness`, `Warfront`, `Sealed Ruin`, `Survivor Level`, `Legacy Gear`, `Enchanting & Anvil`, `Outposts`, `End Raid`, `Required Resource Pack` 아이콘 표시
- `/survivor`: `XP Routes`와 `Battle Readout` 아이콘 표시
- `/legacy`: 들고 있는 장비의 레벨/XP/보너스/Refine/Awaken/Transfer 표시

종료:

```text
stop
```

## 현재 콘텐츠 상태

2026-06-17 기준:

```text
QSMP Frontier | Warfront: dormant at 80, -240 | Expedition: sealed at 116, -204, wards 3, sentinels 0 | Outposts: 0
TrialChamberPro | No chambers registered yet
```

뜻:

- 야외 전장 위치는 스폰 테스트용 좌표에 건설되어 있습니다.
- 레이드가 진행 중이 아니며, 쿨다운도 없습니다.
- 봉인 유적은 스폰 테스트용 좌표에 건설되어 있고 아직 sealed 상태입니다.
- 아직 자연 트라이얼 챔버가 발견/등록되지 않았습니다.

## Frontier 운영 명령

상태:

```text
/frontier status
/frontier warfront status
/frontier expedition status
```

전장 테스트:

```text
/frontier warfront buildspawn
/frontier warfront start
/frontier warfront stop
```

주의:

- `buildspawn`은 스폰 근처에 테스트 전장을 만듭니다.
- 실제 탐험 루프를 보려면 `Frontier Compass`를 따라가게 두는 편이 낫습니다.
- `start`는 주변 참가자가 없어도 레이드를 시작합니다.
- 참가자가 `participant-grace-seconds` 동안 전장에 없으면 레이드는 실패하고 소환 몹과 보스바를 정리합니다.
- `stop`은 active 여부와 관계없이 남은 전장 엔티티와 보스바를 정리합니다.

검증된 QA 절차:

```text
frontier warfront status
frontier warfront buildspawn
frontier warfront start
frontier warfront status
```

기대:

- 시작 직후 `active stage 1, enemies <n>, participants <n>` 표시
- 플레이어 없이 시작하면 약 45초 뒤 `WARFRONT FAILED: The field was abandoned.` 표시
- 이후 `frontier warfront status`가 `dormant at 80, -240`로 돌아옴

레이드 관련 설정:

```yaml
warfront:
  participant-radius: 140.0
  participant-grace-seconds: 45
  transition-delay-ticks: 100
```

- `participant-radius`: 이 반경 안에 있으면 레이드 참가자로 등록되고 보스바 대상이 됩니다.
- `participant-grace-seconds`: 마지막 참가자가 전장을 벗어난 뒤 실패까지 기다리는 시간입니다.
- `transition-delay-ticks`: 웨이브가 비었을 때 다음 단계로 넘어가기 전 대기 시간입니다.

봉인 유적 테스트:

```text
/frontier expedition status
/frontier expedition buildspawn
/frontier expedition reset
```

주의:

- `buildspawn`은 스폰 근처에 봉인 유적을 만듭니다.
- 일반 플레이어는 `Frontier Compass`를 들고 웅크린 채 우클릭해서 유적을 찾습니다.
- ward는 `Crying Obsidian`입니다.
- ward 3개를 깨면 `Ruin Sentinel` 매복이 발생합니다.
- 모든 ward와 sentinel이 없어지면 vault가 열립니다.

검증된 QA 절차:

```text
frontier expedition status
frontier expedition buildspawn
frontier expedition status
```

기대:

- `sealed at <x>, <z>, wards 3, sentinels 0` 표시
- `plugins/QSMPFrontier/expedition.yml`에 vault와 ward 좌표 저장
- ward 블록이 사라지면 다음 tick에서 `RUIN: The vault seal is broken.` 표시

탐험 관련 설정:

```yaml
expeditions:
  discovery-radius: 96.0
  reward-radius: 48.0
  cooldown-minutes: 90
  sentinel-base-count: 2
  sentinel-count-per-player: 1
```

거점 지급:

```text
/frontier give <player> <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>
```

공용 거점 생성:

```text
/frontier outpost buildspawn <wood|quarry|mine|ranch|greenhouse|fishery|warehouse>
```

생산 강제 실행:

```text
/frontier outpost pulse
```

Survivor 테스트:

```text
/survivor
/survivor grant <player> <xp>
/survivor respec
```

운영자 참고:

- `/survivor grant`는 `qsmpfrontier.admin` 권한이 필요합니다.
- `/survivor`는 `Survivor Core` GUI를 열며, 스탯 포인트는 아이콘 클릭으로 투자됩니다.
- 데이터는 `plugins/QSMPFrontier/progression.yml`에 저장됩니다.
- 리스펙 1회 이후에는 메아리 조각 1개가 필요합니다.
- 일반 사냥 XP는 자연 스폰 적대몹만 지급합니다. 스포너, 스폰 알, 명령,
  커스텀 스폰은 제외됩니다.
- 일반 채집 XP는 광물/고대 잔해/자연 석재 계열/원목/성숙 작물에 지급됩니다.
  플레이어가 방금 설치한 블록은 재채굴 XP를 주지 않습니다.

인챈트/모루 테스트:

```text
/give <player> diamond_sword[enchantments={levels:{"minecraft:sharpness":5}}]
```

운영자 참고:

- `purpur.yml`에서 고레벨 인챈트 clamp를 끄고 unsafe enchant 명령을 허용합니다.
- `purpur.yml`에서 모루 누적 비용도 꺼 기존 작업 횟수 때문에 장비가 막히지 않게 합니다.
- `QSMPFrontier`는 모루 결과를 보정해 스케일형 인챈트 10레벨까지 유지합니다.
- 단일성 인챈트 목록은 `plugins/QSMPFrontier/config.yml`의
  `enchanting.single-level`에 있습니다.
- 기존 lore의 `버그 인챈트`/`Bug Enchant`/`Illegal Enchant` 표기는 플레이어
  접속, 픽업, 모루 사용, 인벤토리 닫기, 10초 주기 점검 뒤 자동 제거됩니다.
- 정리된 플레이어에게는 액션바와 인챈트 테이블 소리가 3초 쿨다운으로 표시됩니다.

Legacy 테스트:

```text
/legacy
/legacy refine
/legacy awaken
/legacy transfer
```

운영자 참고:

- Legacy 데이터는 아이템 PDC와 lore에 저장됩니다.
- 무기/도구/방어구/방패/활/쇠뇌/삼지창/메이스가 대상입니다.
- 대상 아이템은 인벤토리 합류, 픽업, 핫바 이동, 장착/교체 뒤 자동으로 Legacy가 붙습니다.
- 워프론트/유적/드래곤/위더 보상은 장착 장비에 Legacy XP를 줍니다.
- Legacy lore에는 피해/공속/채굴/내구/방어 보너스와 refine 실패 확률이 표시됩니다.
- `/legacy refine`은 네더라이트 조각을 소모합니다. +3부터는 Void Scale도 소모합니다.
- Refine 실패는 재료를 소모하고 내구도를 손상시키지만 장비를 파괴하지 않으며, 실패 XP를 지급합니다.
- Legacy Lv.5 이상 장비는 낮은 타격/채굴 공명, Refine +3 이상 장비는 강한 공명/번개 연출을 냅니다.
- `/legacy awaken`은 Dragon Heart를 소모하고 Void Burst 전투 발동 효과를 붙입니다.
- `/legacy transfer`는 Memory Shard를 소모하고, 메인핸드 원본 장비는 유지하며,
  오프핸드 장비의 기존 Legacy 기억은 덮어씁니다.
- `/legacy bind`는 호환용으로만 남아 있으며 일반 안내/탭완성에는 노출하지 않습니다.

전투 무적 틱:

- `combat.mob-no-damage-ticks`: 기본 `0`, 몹은 피격 후 무적 시간이 없습니다.
- `combat.player-no-damage-ticks`: 기본 `2`, 플레이어는 피격 후 2틱만 보호됩니다.

드래곤 레이드 참고:

```yaml
dragon:
  enabled: true
  phase-one-health-ratio: 0.65
  phase-two-health-ratio: 0.35
  resonance-stones: 3
  resonance-shield-damage-multiplier: 0.25
```

- True Ending 데이터팩 위에 QSMPFrontier가 체력/피해 스케일과 공명석 페이즈를 얹습니다.
- 드래곤이 죽거나 서버가 꺼지면 공명석 블록은 정리됩니다.
- 처치 보상 `Dragon Heart`, `Void Scale`, `Memory Shard`, 확률 `Ender Core`는 `FrontierItems` PDC 커스텀 아이템입니다.
- `Dragon Heart`: `/legacy awaken`
- `Void Scale`: refine +3 이상, `Memory Shard` 제작
- `Memory Shard`: `/legacy transfer`
- `Ender Core`: `Outpost Upgrade Kit III` 추가 제작법

Codex/인게임 안내 참고:

- 플레이어 첫 접속 시 `Frontier Compass`와 `QSMP Codex`가 1회 지급됩니다.
- 이후 접속 때도 커스텀 제작법은 다시 discover 처리됩니다.
- `QSMP Codex` 일반 우클릭은 written book, 웅크린 우클릭은 아이콘 GUI입니다.
- GUI는 전장/유적 나침반 조율, Survivor/Legacy 확인, 거점 제작법 해금, 엔드 레이드 요약으로 연결됩니다.

연출 설정:

```yaml
cinematics:
  enabled: true
  compass-trails: true
```

- `enabled: false`면 QSMPFrontier 연출 레이어를 끕니다.
- `compass-trails: false`면 나침반 길잡이 파티클만 끕니다.
- 성능 문제를 볼 때는 `compass-trails`만 먼저 끄는 편이 좋습니다.

리소스팩 운영:

```bash
uv run scripts/build_resource_pack.py --apply-server-properties
```

결과:

- `resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.zip`
- `resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.sha1`
- [server.properties](../server.properties)의 `require-resource-pack=true`
- [server.properties](../server.properties)의 `resource-pack-prompt={"text":"..."}`
- [server.properties](../server.properties)의 `resource-pack-sha1=<현재 SHA1>`

서버 시작:

- `./server.sh start`는 리소스팩을 자동 빌드하고 SHA1을 갱신합니다.
- `QSMPFrontier`는 `resource-pack.port` 기본 `25566`에서 zip을 HTTP로 서빙합니다.
- 접속 플레이어에게 required pack을 전송합니다.

외부 접속 주의:

- 기본 URL `http://127.0.0.1:25566/qsmp-frontier-pack.zip`은 서버 PC 로컬 테스트용입니다.
- 친구들이 인터넷으로 들어오면 [server.env](../server.env)의 `RESOURCE_PACK_PUBLIC_URL`을 공인 IP/도메인으로 바꿉니다.
- TCP `25566` 포트도 공유기/방화벽에서 열어야 합니다.

확인:

```bash
curl -I http://127.0.0.1:25566/qsmp-frontier-pack.zip
cat resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.sha1
```

동료 역할 수리:

```text
/frontier role <vanguard|ranger|medic|gatherer>
```

플레이어가 바라보는 내 동료에게 역할을 붙입니다. 일반 플레이에서는 휘슬 사용을 권장합니다.

## Companion 운영 명령

동료 정보:

```text
/companion info
```

아이템 지급:

```text
/companion give <player> <cryopod|iron|diamond|netherite|charm>
```

Happy Ghast 수리 결속:

```text
/companion claim
```

설정 리로드:

```text
/companion reload
```

## TrialChamberPro 운영 명령

권한 주의:

- `tcp.stats`, `tcp.leaderboard`는 기본 공개 권한입니다.
- 현재 JAR 메타데이터에는 `/tcp` 루트 명령 권한이 `tcp.admin`으로도 선언되어 있습니다.
- 비OP가 `/tcp stats`나 `/tcp leaderboard`를 못 쓰면 권한 레이어에서 `/tcp` 접근을 풀어야 합니다.

목록:

```text
/tcp list
```

정보:

```text
/tcp info [chamber]
```

GUI:

```text
/tcp menu
```

강제 리셋:

```text
/tcp reset <chamber>
```

스냅샷:

```text
/tcp snapshot create <chamber>
/tcp snapshot update <chamber>
/tcp snapshot restore <chamber>
```

챔버 일시 정지:

```text
/tcp pause <chamber>
/tcp resume <chamber>
```

보상 테이블:

```text
/tcp loot list
/tcp loot info <chamber>
/tcp loot set <chamber> <normal|ominous> <table>
/tcp loot clear <chamber> [normal|ominous|all]
```

키 지급:

```text
/tcp key give <player> <amount> [normal|ominous]
```

리로드:

```text
/tcp reload
```

주의:

- 현재 WorldEdit/FAWE가 없어서 schematic paste 기능은 비활성입니다.
- 자연 챔버 반복 플레이에는 WorldEdit이 필요 없습니다.

## Chunky 운영

오버월드 5000블록 예시:

```text
chunky world world
chunky radius 5000
chunky start
```

네더/엔드도 별도 실행합니다.

```text
chunky world world_nether
chunky radius 2500
chunky start
```

```text
chunky world world_the_end
chunky radius 2500
chunky start
```

중단:

```text
chunky pause
chunky cancel
```

## 데이터팩 관리

주입:

```bash
./world.sh inject
```

상태:

```bash
./world.sh check
```

월드 리셋:

```bash
./world.sh reset CONFIRM
```

주의:

- 리셋은 기존 월드를 `backups/worlds`에 백업하고 삭제합니다.
- TrialChamberPro 월드 상태와 Frontier 전장/거점 상태도 초기화됩니다.
- 완전히 새 지형을 원할 때만 사용합니다.

## 플러그인 관리

최신 호환 버전 resolve:

```bash
./plugins.sh resolve
```

다운로드/설치:

```bash
./plugins.sh update
```

해시 확인:

```bash
./plugins.sh check
```

현재 lock:

- Chunky `1.5.3`
- AntiVillagerLag `3.0.8`
- AttributeSwapFix `1.1.0`
- AxGraves `1.28.1`
- AxInventoryRestore `3.13.0`
- TrialChamberPro `1.5.9-mc26`
- LevelledMobs `4.5.3`

## 테스트 체크리스트

새 콘텐츠 확인:

1. 서버 시작
2. `datapack list enabled`로 데이터팩 활성 확인
3. 새 청크 탐험
4. 필수 리소스팩 다운로드/적용 확인
5. 첫 접속 지급품 `Frontier Compass`, `QSMP Codex` 확인
6. `QSMP Codex` 일반 우클릭 책 열림 확인
7. `QSMP Codex` 웅크린 우클릭 GUI 확인
8. `Frontier Compass` 우클릭/웅크린 우클릭 확인
9. 동물 길들이기
10. `/companion info` 또는 빈손 웅크리기 우클릭 확인
11. `Tactical Whistle` 역할 순환/배정 확인
12. 거점 코어 지급 후 설치 확인
13. `/frontier outpost pulse`로 생산 확인
14. 전장 주변 로드 후 자동 건설 확인
15. 엔더 드래곤 전투에서 공명석 페이즈 확인
16. 자연 트라이얼 챔버 발견 후 `/tcp list` 확인

서버 로그에서 무시 가능한 것:

- `Could not save messages.yml because messages.yml already exists`
- `Could not save loot.yml because loot.yml already exists`
- `WorldEdit/FAWE not found - schematic features disabled`

무시하면 안 되는 것:

- `messages.yml missing key`
- `config could not be parsed`
- `Datapack check failed`
- `Plugin lock mismatch`
- `Java version 21 found`
