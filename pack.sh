#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# MusicDav 打包：升版本号 → 构建 → 安装到已连接的设备。
# 只用 bash 3.2 也能跑的语法（macOS 自带 /bin/bash 就是 3.2），命令也都按 BSD 版写。

usage() {
    cat <<'EOF'
用法：
  ./pack.sh                  debug 打包（自动升版本号），装到连接的设备
  ./pack.sh -s <设备>        指定设备：serial 全称或能唯一匹配的片段（如 10AFB622 / emulator）
  ./pack.sh --all            装到所有在线设备
  ./pack.sh --run            装完顺带启动 App
  ./pack.sh --release        release 打包（本项目还没配签名，出的是 unsigned 包）
  ./pack.sh --no-install     只打包不安装
  ./pack.sh --no-bump        不动版本号

版本号：改 app/build.gradle 的 versionCode(+1) 和 versionName 最后一段(+1)，1.0 → 1.1。
设备选择：只有一台直接装；多台时交互终端让你选，非交互（脚本/agent 里跑）则列出命令。
EOF
}

VARIANT="debug"
DO_INSTALL=1
DO_BUMP=1
DO_RUN=0
INSTALL_ALL=0
TARGET=""
GRADLE_FILE="app/build.gradle"
LAUNCH_COMPONENT="com.spotify.music/tech.xvanturing.musicdav.MainActivity"

while [ $# -gt 0 ]; do
    case "$1" in
        --debug) VARIANT="debug" ;;
        --release) VARIANT="release" ;;
        --no-install) DO_INSTALL=0 ;;
        --no-bump) DO_BUMP=0 ;;
        --run) DO_RUN=1 ;;
        --all) INSTALL_ALL=1 ;;
        -s) shift; TARGET="${1:-}"; [ -n "$TARGET" ] || { echo "-s 后面要跟设备名" >&2; exit 2; } ;;
        -s*) TARGET="${1#-s}" ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数：$1（-h 看用法）" >&2; exit 2 ;;
    esac
    shift
done

# --- adb 定位：ANDROID_HOME → local.properties 的 sdk.dir → PATH ---
SDK_DIR="${ANDROID_HOME:-}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
    SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)
fi
ADB="$SDK_DIR/platform-tools/adb"
if [ ! -x "$ADB" ]; then
    ADB=$(command -v adb || true)
fi

read_version_code() { sed -n 's/^ *versionCode \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1; }
read_version_name() { sed -n 's/^ *versionName "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1; }

# --- 升版本号 ---
if [ "$DO_BUMP" -eq 1 ]; then
    CODE=$(read_version_code)
    NAME=$(read_version_name)
    if [ -z "$CODE" ] || ! echo "$NAME" | grep -qE '^[0-9]+(\.[0-9]+)*$'; then
        echo "解析版本号失败：$GRADLE_FILE 里应有 versionCode N / versionName \"x.y\"。" >&2
        echo "请手动修正，或用 --no-bump 跳过。" >&2
        exit 1
    fi
    NEW_CODE=$((CODE + 1))
    # versionName 最后一段 +1，前面几段原样保留（1.0 → 1.1；1.2.9 → 1.2.10；7 → 8）
    NAME_HEAD=${NAME%.*}
    if [ "$NAME_HEAD" = "$NAME" ]; then
        NEW_NAME=$((NAME + 1))
    else
        NAME_TAIL=${NAME##*.}
        NEW_NAME="$NAME_HEAD.$((NAME_TAIL + 1))"
    fi
    sed -i '' -e "s/^\( *versionCode \)$CODE\$/\1$NEW_CODE/" \
              -e "s/^\( *versionName \)\"$NAME\"\$/\1\"$NEW_NAME\"/" "$GRADLE_FILE"
    if [ "$(read_version_code)" != "$NEW_CODE" ] || [ "$(read_version_name)" != "$NEW_NAME" ]; then
        echo "版本号写回失败，$GRADLE_FILE 可能被改过格式，请检查。" >&2
        exit 1
    fi
    echo "==> 版本：$NAME($CODE) → $NEW_NAME($NEW_CODE)"
else
    echo "==> 版本：$(read_version_name)($(read_version_code))（未升）"
fi

# --- 构建 ---
if [ "$VARIANT" = "debug" ]; then
    TASK="assembleDebug"; LINT_TASK="lintDebug"
else
    TASK="assembleRelease"; LINT_TASK="lintRelease"
fi
echo "==> ./gradlew $TASK -x $LINT_TASK"
./gradlew "$TASK" -x "$LINT_TASK"

# 产物：没配签名时 release 出的是 -unsigned
APK=""
for candidate in \
    "app/build/outputs/apk/$VARIANT/app-$VARIANT.apk" \
    "app/build/outputs/apk/$VARIANT/app-$VARIANT-unsigned.apk"
do
    if [ -f "$candidate" ]; then APK="$candidate"; break; fi
done
if [ -z "$APK" ]; then
    echo "没找到产物：app/build/outputs/apk/$VARIANT/" >&2
    exit 1
fi
echo "==> 产物：$APK ($(du -h "$APK" | cut -f1))"

case "$APK" in
    *-unsigned.apk)
        echo "==> 这是未签名包，装不上。要装请先在 app/build.gradle 里配 signingConfigs，或用 debug 打包。" >&2
        exit 1
        ;;
esac

# --- 安装 ---
if [ "$DO_INSTALL" -eq 0 ]; then exit 0; fi
if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then
    echo "==> 找不到 adb，跳过安装"
    exit 0
fi

DEVS=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(printf '%s\n' "$DEVS" | grep -c . || true)

# 每个 adb 调用都要 </dev/null：adb install / adb shell 会读 stdin，
# 在 while read 循环里会把剩下的设备行全吞掉（实测只列出/只装了第一台）
install_to() {
    dev="$1"
    echo "==> 安装到 $dev"
    "$ADB" -s "$dev" install -r "$APK" </dev/null
    if [ "$DO_RUN" -eq 1 ]; then
        echo "==> 启动 $LAUNCH_COMPONENT"
        "$ADB" -s "$dev" shell am start -n "$LAUNCH_COMPONENT" </dev/null >/dev/null
    fi
}

install_all() {
    # 用 here-string 而不是管道：管道会把循环体丢进子 shell，里面 install 失败 set -e 拦不住
    while IFS= read -r d; do
        [ -n "$d" ] && install_to "$d"
    done <<< "$DEVS"
}

if [ "$COUNT" -eq 0 ]; then
    echo "==> 没有在线的 adb 设备，跳过安装"
    exit 0
fi

# -s 指定：serial 全称或唯一匹配的片段
if [ -n "$TARGET" ]; then
    MATCHED=$(printf '%s\n' "$DEVS" | grep -F "$TARGET" || true)
    MCOUNT=$(printf '%s\n' "$MATCHED" | grep -c . || true)
    if [ "$MCOUNT" -eq 0 ]; then
        echo "没有匹配 \"$TARGET\" 的设备。在线设备：" >&2
        printf '%s\n' "$DEVS" | sed 's/^/    /' >&2
        exit 1
    fi
    if [ "$MCOUNT" -gt 1 ]; then
        echo "\"$TARGET\" 匹配到多台设备，请写得更具体：" >&2
        printf '%s\n' "$MATCHED" | sed 's/^/    /' >&2
        exit 1
    fi
    install_to "$MATCHED"
    exit 0
fi

if [ "$INSTALL_ALL" -eq 1 ]; then
    install_all
    exit 0
fi

if [ "$COUNT" -eq 1 ]; then
    install_to "$DEVS"
    exit 0
fi

# 多台设备：交互终端让选，非交互就把命令列出来
echo "==> 检测到 $COUNT 台在线设备："
INDEX=0
while IFS= read -r d; do
    [ -n "$d" ] || continue
    INDEX=$((INDEX + 1))
    MODEL=$("$ADB" -s "$d" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')
    echo "    $INDEX) $d  ${MODEL:-?}"
done <<< "$DEVS"

if [ -t 0 ]; then
    printf '选哪台？(1-%s，回车 = 全部装，q = 放弃) ' "$COUNT"
    read -r PICK
    case "$PICK" in
        q|Q) exit 0 ;;
        "") install_all ;;
        *[!0-9]*) echo "输入无效" >&2; exit 1 ;;
        *)
            if [ "$PICK" -ge 1 ] && [ "$PICK" -le "$COUNT" ]; then
                install_to "$(printf '%s\n' "$DEVS" | sed -n "${PICK}p")"
            else
                echo "序号超出范围" >&2
                exit 1
            fi
            ;;
    esac
else
    echo "==> 非交互环境，没自动安装。用 -s 指定，或 --all 全装："
    printf '%s\n' "$DEVS" | sed 's|^|    ./pack.sh --no-bump -s |'
fi
