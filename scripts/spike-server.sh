#!/usr/bin/env bash
# v0.0.1 接続スパイク: Fabric 専用サーバー + Geyser-Fabric + Hydraulic + fixture MOD を隔離フォルダーで起動し、
# 起動ログ（登録順序・Geyser/Hydraulic の初期化・ポート）を証跡として保存する。
#
# 使い方:
#   MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh [--keep-running]
#
# - Minecraft EULA（https://aka.ms/MinecraftEULA）への同意は実行者が環境変数で明示する。既定では起動しない。
# - 既定では Geyser の起動完了を確認した後に stop で終了する。--keep-running では Bedrock 実機接続のため
#   LAN 向けに待ち受けたまま起動し続ける（LAN 内の誰でも接続できる点に注意）。
# - 取得物は全て固定版・SHA-256 照合済み。照合に失敗したら起動しない。
# - 書き込むのはサーバー作業フォルダー（既定 local/spike-server）と証跡フォルダー（既定 evidence/raw）だけ。
#   作業フォルダーは空か、このスクリプトが作った印（.modbridge-spike）のあるフォルダーに限る。
# - 保存するログと画面表示は IP アドレスを伏せ字にする。
# - 取得 MOD（platform-fabric）をビルド済みなら一緒に入れ、実行時レジストリを <作業フォルダー>/registry-dump.json に書き出す。
#   静的解析との突き合わせは modbridge scan <作業フォルダー>/mods --runtime <作業フォルダー>/registry-dump.json。
# - 要 bash 4 以上（Linux / Git for Windows）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SPIKE_DIR:-$ROOT/local/spike-server}"
EVIDENCE="${SPIKE_EVIDENCE_DIR:-$ROOT/evidence/raw}"
TIMEOUT_SEC="${SPIKE_TIMEOUT_SEC:-600}"
KEEP_RUNNING=false
mkdir -p "$WORK" "$EVIDENCE"
WORK="$(cd "$WORK" && pwd)"; EVIDENCE="$(cd "$EVIDENCE" && pwd)"   # 相対パス指定でも cd 後に壊れないよう絶対化
[[ "${1:-}" == "--keep-running" ]] && KEEP_RUNNING=true

# 固定版（docs/adr/0002 と gradle/libs.versions.toml を同時に更新する。MC と LOADER は CI が照合する）
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
# IPv4 と IPv6（角括弧付き・括弧なし・:: 省略形）を伏せ字にする。時刻 hh:mm:ss（コロン 2 個・:: なし）は対象外。
redact() {
  sed -u -E \
    -e 's/\[[0-9a-fA-F:]*::[0-9a-fA-F:]*\]/[IPv6]/g' \
    -e 's/\[[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){3,7}\]/[IPv6]/g' \
    -e 's/(^|[^0-9a-fA-F:])[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,7}/\1IPv6/g' \
    -e 's/[0-9]{1,3}(\.[0-9]{1,3}){3}/x.x.x.x/g'
}

[[ "${MODBRIDGE_ACCEPT_EULA:-}" == "true" ]] || die "Minecraft EULA への同意が必要。同意する場合だけ MODBRIDGE_ACCEPT_EULA=true を付けて実行する"
command -v java >/dev/null || die "java が見つからない"
command -v jps >/dev/null || die "jps が見つからない（JRE ではなく JDK 25 以上を使う）"
JAVA_FEATURE="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
(( JAVA_FEATURE >= 25 )) || die "Java 25 以上が必要（検出: $JAVA_FEATURE）"

MOD_VERSION="$(sed -n 's/^mod_version=//p' "$ROOT/fixtures/fabric-fixture/gradle.properties")"
FIXTURE_JAR="$ROOT/fixtures/fabric-fixture/build/libs/pmb-fabric-fixture-$MOD_VERSION.jar"
[[ -f "$FIXTURE_JAR" ]] || die "fixture JAR が無い。先に (cd fixtures/fabric-fixture && ./gradlew build) を実行する"
DUMP_VERSION="$(sed -n 's/^mod_version=//p' "$ROOT/platform-fabric/gradle.properties")"
DUMP_JAR="$ROOT/platform-fabric/build/libs/modbridge-registry-dump-$DUMP_VERSION.jar"

# 作業フォルダーの安全確認: 新規・空・このスクリプトの印つきのいずれかでなければ触らない
if [[ -d "$WORK" && ! -f "$WORK/.modbridge-spike" && -n "$(ls -A "$WORK" 2>/dev/null)" ]]; then
  die "作業フォルダー $WORK は空でなく、スパイク用の印（.modbridge-spike）も無い。別のフォルダーを SPIKE_DIR に指定する"
fi
mkdir -p "$WORK/mods" "$EVIDENCE"
touch "$WORK/.modbridge-spike"
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
DUMP_FILE="$WORK/registry-dump.json"
rm -f "$DUMP_FILE"
if [[ -f "$DUMP_JAR" ]]; then
  cp "$DUMP_JAR" mods/modbridge-registry-dump.jar
else
  rm -f mods/modbridge-registry-dump.jar
  echo "[spike] 取得 MOD が無いので、実行時レジストリは書き出さない（作るには (cd platform-fabric && ./gradlew build)）"
fi

echo "eula=true" > eula.txt
# Java 側は常に localhost のみで待ち受ける（Bedrock 側の待受は Geyser 設定で別に決める）
touch server.properties
set_prop() {
  if grep -q "^$1=" server.properties; then sed -i "s|^$1=.*|$1=$2|" server.properties; else echo "$1=$2" >> server.properties; fi
}
set_prop online-mode false
set_prop server-ip 127.0.0.1
set_prop server-port 25565
set_prop level-name spike-world

# Geyser 設定（初回起動で生成されるため、2 回目以降の起動で有効）
# - 外部 STUN 問い合わせを止める
# - RakNet も受ける（transport: both、NetherNet は UDP 19133）。従来の「IP とポート指定」で実機から接続できるようにする
# - 自動停止モードでは Bedrock 待受を localhost に限定する
GEYSER_CFG=config/Geyser-Fabric/config.yml
FIRST_RUN=true
if [[ -f "$GEYSER_CFG" ]]; then
  FIRST_RUN=false
  sed -i -e 's/^    stun-discovery: true$/    stun-discovery: false/' \
         -e 's/^  transport: nethernet$/  transport: both/' \
         -e 's/^  webrtc-port: 0$/  webrtc-port: 19133/' "$GEYSER_CFG"
  if $KEEP_RUNNING; then
    sed -i 's/^  address: 127\.0\.0\.1$/  address: 0.0.0.0/' "$GEYSER_CFG"
  else
    sed -i 's/^  address: 0\.0\.0\.0$/  address: 127.0.0.1/' "$GEYSER_CFG"
  fi
fi
$FIRST_RUN && echo "[spike] 初回起動: Geyser 設定を生成する（この回だけ既定設定のため STUN 問い合わせと 0.0.0.0 待受が発生する）"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$EVIDENCE/spike-server-$STAMP.log"
echo "[spike] 起動: ログ → $LOG"
{
  echo "# spike-server $STAMP first_run=$FIRST_RUN keep_running=$KEEP_RUNNING"
  java -version 2>&1
  for f in "${!SHA[@]}"; do echo "sha256 ${SHA[$f]} $f"; done
  sha256sum mods/pmb-fabric-fixture.jar
  [[ -f mods/modbridge-registry-dump.jar ]] && sha256sum mods/modbridge-registry-dump.jar
  grep -E '^  (address|port|transport|webrtc-port):|^    stun-discovery:' "$GEYSER_CFG" 2>/dev/null || true
} | redact > "$LOG"

JAVA_OPTS=(-Xmx2G -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 "-Dmodbridge.spike=$STAMP")
[[ -f mods/modbridge-registry-dump.jar ]] && JAVA_OPTS+=("-Dmodbridge.registryDump=$DUMP_FILE")
if $KEEP_RUNNING; then
  java "${JAVA_OPTS[@]}" -jar fabric-server-launch.jar nogui 2>&1 | redact | tee -a "$LOG"
  exit 0
fi

# この起動の java だけを OS の PID で強制終了する（Git Bash では bash 側の kill が Windows の java に届かないため）
force_kill_server() {
  local pid
  pid="$(jps -v 2>/dev/null | awk -v id="-Dmodbridge.spike=$STAMP" 'index($0, id) {print $1}')"
  [[ -n "$pid" ]] || return 0
  echo "[spike] 強制終了: java pid $pid" >&2
  if command -v taskkill >/dev/null 2>&1; then taskkill //F //PID "$pid" >/dev/null 2>&1 || true; else kill -9 "$pid" 2>/dev/null || true; fi
}

# 停止要求ファイルが置かれるまで標準入力を開いたままにし、置かれたら stop を送る。
# （Git Bash では名前付きパイプが Windows ネイティブプロセスに届かないため、通常のパイプを使う）
STOP_REQ="$WORK/.spike-stop"; EXITED="$WORK/.spike-exit"; rm -f "$STOP_REQ" "$EXITED"
{ until [[ -f "$STOP_REQ" || -f "$EXITED" ]]; do sleep 1; done; echo stop; } \
  | { java "${JAVA_OPTS[@]}" -jar fabric-server-launch.jar nogui 2>&1 | redact >> "$LOG"; echo "${PIPESTATUS[0]}" > "$EXITED"; } &
PIPE_PID=$!
# 中断（Ctrl-C・kill）時もサーバーを残さない
trap 'force_kill_server; touch "$STOP_REQ"; exit 130' INT TERM

# Geyser の起動完了行（"Run /geyser help"）で成功と判定する。バニラの Done だけでは成功にしない。
result=""
deadline=$(( $(date +%s) + TIMEOUT_SEC ))
while :; do
  if grep -q 'Run /geyser help' "$LOG"; then result=started; break; fi
  if [[ -f "$EXITED" ]]; then result="server exited early (exit $(cat "$EXITED"))"; break; fi
  if grep -qE 'Failed to start the minecraft server|Exception in server tick loop' "$LOG"; then result="server failed"; break; fi
  if (( $(date +%s) >= deadline )); then result="timeout (${TIMEOUT_SEC}s)"; break; fi
  sleep 2
done
if [[ "$result" == "started" ]]; then
  touch "$STOP_REQ"   # 起動完了後だけ stop を送る（起動中の stop は受け付けられない）
  for _ in $(seq 60); do [[ -f "$EXITED" ]] && break; sleep 2; done
fi
if [[ ! -f "$EXITED" ]]; then
  [[ "$result" == "started" ]] && echo "[spike] 警告: stop 後 120 秒経っても終了しない" >&2
  force_kill_server
  touch "$STOP_REQ"
  for _ in $(seq 15); do [[ -f "$EXITED" ]] && break; sleep 2; done
fi
wait "$PIPE_PID" 2>/dev/null || true
trap - INT TERM
rm -f "$STOP_REQ" "$EXITED"

[[ "$result" == "started" ]] || die "起動を確認できなかった（$result）。ログ: $LOG"
echo "[spike] 停止完了。要点:"
grep -nE 'Loading Minecraft|pmb-fixture|pmb_fixture|modbridge\]|Hydraulic|Geyser version|Registered|Done \(|signaling|RakNet|ERROR|Exception' "$LOG" | head -n 80 || true
if [[ -f mods/modbridge-registry-dump.jar ]]; then
  [[ -f "$DUMP_FILE" ]] || die "取得 MOD を入れたが、実行時レジストリが書き出されていない。ログ: $LOG"
  echo "[spike] 実行時レジストリ: $DUMP_FILE"
fi
