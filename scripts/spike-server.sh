#!/usr/bin/env bash
# v0.0.1 接続スパイク: Fabric 専用サーバー + Geyser-Fabric + Hydraulic + fixture MOD を隔離フォルダーで起動し、
# 起動ログ（登録順序・Geyser/Hydraulic の初期化・ポート）を証跡として保存する。
#
# 使い方:
#   MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh [--keep-running]
#
# - Minecraft EULA（https://aka.ms/MinecraftEULA）への同意は実行者が環境変数で明示する。既定では起動しない。
# - 既定では起動完了を確認した後に stop で終了する。--keep-running では Bedrock 実機接続のため起動したままにする。
# - 取得物は全て固定版・SHA-256 照合済み。照合に失敗したら起動しない。
# - 作業フォルダー（既定 local/spike-server）以外は変更しない。ワールドはこのフォルダー内に新規作成する。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SPIKE_DIR:-$ROOT/local/spike-server}"
EVIDENCE="${SPIKE_EVIDENCE_DIR:-$ROOT/evidence/raw}"
TIMEOUT_SEC="${SPIKE_TIMEOUT_SEC:-600}"
KEEP_RUNNING=false
[[ "${1:-}" == "--keep-running" ]] && KEEP_RUNNING=true

# 固定版（docs/adr/0002 と gradle/libs.versions.toml を同時に更新する）
MC=26.3
LOADER=0.20.1
INSTALLER=1.1.2
declare -A URL SHA
URL[fabric-server-launch.jar]="https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar"
SHA[fabric-server-launch.jar]=70446947655b4f8f286aba896aa4c19a255e8ca8a730636ef2a6017ae4415d95
URL[mods/fabric-api.jar]="https://cdn.modrinth.com/data/P7dR8mSH/versions/Zns5SSRA/fabric-api-0.166.2%2B26.3.jar"
SHA[mods/fabric-api.jar]=e8acacc7836f39237c83c2a664b039647ee36f93822797acc40cda44666002ea
URL[mods/Geyser-Fabric.jar]="https://download.geysermc.org/v2/projects/geyser/versions/2.12.1/builds/1312/downloads/fabric"
SHA[mods/Geyser-Fabric.jar]=9c059b51af0bd534922ca9633996fd124543291d137b9373edc95fc1a0d59276
URL[mods/hydraulic-fabric.jar]="https://download.geysermc.org/v2/projects/hydraulic/versions/1.0.0/builds/132/downloads/fabric"
SHA[mods/hydraulic-fabric.jar]=d08f5f5f3092d742cf5e95f0c0fd071ac1a9f4f8351486a4da659968a4766f90

die() { echo "[spike] 中止: $*" >&2; exit 1; }

[[ "${MODBRIDGE_ACCEPT_EULA:-}" == "true" ]] || die "Minecraft EULA への同意が必要。同意する場合だけ MODBRIDGE_ACCEPT_EULA=true を付けて実行する"
command -v java >/dev/null || die "java が見つからない"
JAVA_FEATURE="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
(( JAVA_FEATURE >= 25 )) || die "Java 25 以上が必要（検出: $JAVA_FEATURE）"

FIXTURE_JAR="$(ls "$ROOT"/fixtures/fabric-fixture/build/libs/pmb-fabric-fixture-*.jar 2>/dev/null | grep -v sources | head -n1 || true)"
[[ -n "$FIXTURE_JAR" ]] || die "fixture JAR が無い。先に (cd fixtures/fabric-fixture && ./gradlew build) を実行する"

mkdir -p "$WORK/mods" "$EVIDENCE"
cd "$WORK"

for f in "${!URL[@]}"; do
  if [[ ! -f "$f" ]] || ! echo "${SHA[$f]}  $f" | sha256sum -c --status; then
    echo "[spike] 取得: $f"
    curl -fsSL -A "modbridge-spike" -o "$f.part" "${URL[$f]}"
    echo "${SHA[$f]}  $f.part" | sha256sum -c --status || { rm -f "$f.part"; die "SHA-256 不一致: $f"; }
    mv "$f.part" "$f"
  fi
done
cp "$FIXTURE_JAR" mods/pmb-fabric-fixture.jar

echo "eula=true" > eula.txt
if [[ ! -f server.properties ]]; then
  cat > server.properties <<'PROPS'
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-name=spike-world
motd=PuiModBridge spike (isolated)
PROPS
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$EVIDENCE/spike-server-$STAMP.log"
echo "[spike] 起動: ログ → $LOG"
{
  echo "# spike-server $STAMP"
  java -version 2>&1
  for f in "${!SHA[@]}"; do echo "sha256 ${SHA[$f]} $f"; done
  sha256sum mods/pmb-fabric-fixture.jar
} > "$LOG"

# 自動停止モードでは外部 STUN 問い合わせを止め、Bedrock 待受を localhost に限定する（2 回目以降の起動で有効）。
GEYSER_CFG=config/Geyser-Fabric/config.yml
if [[ -f "$GEYSER_CFG" ]]; then
  sed -i 's/^    stun-discovery: true$/    stun-discovery: false/' "$GEYSER_CFG"
  if $KEEP_RUNNING; then
    sed -i 's/^  address: 127\.0\.0\.1$/  address: 0.0.0.0/' "$GEYSER_CFG"
  else
    sed -i 's/^  address: 0\.0\.0\.0$/  address: 127.0.0.1/' "$GEYSER_CFG"
  fi
fi

JAVA_OPTS=(-Xmx2G -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8)
if $KEEP_RUNNING; then
  java "${JAVA_OPTS[@]}" -jar fabric-server-launch.jar nogui 2>&1 | tee -a "$LOG"
  exit 0
fi

# 標準入力への書き手が起動完了を監視して stop を送る（Windows でも動くよう名前付きパイプは使わない）。
STATUS="$WORK/.spike-status"; rm -f "$STATUS"
{
  deadline=$(( $(date +%s) + TIMEOUT_SEC ))
  until grep -q 'Done (' "$LOG" 2>/dev/null; do
    if (( $(date +%s) >= deadline )); then echo timeout > "$STATUS"; break; fi
    if grep -qE 'Failed to start the minecraft server|Stopping server' "$LOG"; then echo "server failed" > "$STATUS"; break; fi
    sleep 2
  done
  # Geyser の起動完了（Done の後に出る 2 行目の Done）を最大 60 秒待つ
  for _ in $(seq 30); do (( $(grep -c 'Done (' "$LOG") >= 2 )) && break; sleep 2; done
  [[ -f "$STATUS" ]] || echo started > "$STATUS"
  echo stop
} | java "${JAVA_OPTS[@]}" -jar fabric-server-launch.jar nogui >>"$LOG" 2>&1 || true

[[ "$(cat "$STATUS" 2>/dev/null)" == "started" ]] || die "起動を確認できなかった（$(cat "$STATUS" 2>/dev/null || echo 'server exited early')）。ログ: $LOG"
echo "[spike] 停止完了。要点（IP アドレスは伏せ字）:"
grep -nE 'Loading Minecraft|pmb-fixture|pmb_fixture|Hydraulic|Geyser version|Registered|Done \(|signaling|ERROR|Exception' "$LOG" \
  | sed -E 's/\[[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,}\]/[IPv6]/g; s/[0-9]{1,3}(\.[0-9]{1,3}){3}/x.x.x.x/g' | head -n 80 || true
