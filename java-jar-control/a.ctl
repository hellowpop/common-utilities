#!/usr/bin/env bash
# Linux executable-JAR controller. Configuration is trusted Bash code.
set -euo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage() { printf 'Usage: %s {start|stop|status|staus|log}\n' "$0"; }
[[ $# == 1 ]] || { usage >&2; exit 2; }
case "$1" in
    start|stop|status|staus|log) ACTION=$1 ;;
    help|--help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac
[[ $(uname -s) == Linux ]] || fail 'Linux is required.'
BASE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
cd -- "$BASE_DIR"

# These settings must come from configure, never defaults or inherited environment.
unset APP_NAME JAR_FILE
JAVA_HOME=${JAVA_HOME:-}
MEMORY_OPTS=(-Xms256m -Xmx512m)
GC_OPTS=(-XX:+UseG1GC)
JAVA_OPTS=()
AGENT_OPTS=()
APP_ARGS=()
START_WAIT=2
STOP_TIMEOUT=30
FORCE_STOP=false
CONFIG_FILE="$BASE_DIR/configure"
[[ -r "$CONFIG_FILE" ]] || fail "Configuration is not readable: $CONFIG_FILE"
# shellcheck source=/dev/null
source "$CONFIG_FILE"
[[ ${APP_NAME:-} =~ [^[:space:]] ]] || fail 'APP_NAME must be specified in configure (non-blank).'
[[ ${JAR_FILE:-} =~ [^[:space:]] ]] || fail 'JAR_FILE must be specified in configure (non-blank).'
[[ "$APP_NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || fail 'Invalid APP_NAME in configure.'
for setting in START_WAIT STOP_TIMEOUT; do
    [[ ${!setting} =~ ^[1-9][0-9]{0,4}$ ]] || fail "$setting must be an integer from 1 to 99999."
    # Interpret leading digits as decimal, never as Bash octal.
    printf -v "$setting" '%d' "$((10#${!setting}))"
done
[[ "$FORCE_STOP" == true || "$FORCE_STOP" == false ]] || fail 'FORCE_STOP must be true or false.'
for dependency in flock nohup; do
    command -v "$dependency" >/dev/null || fail "Missing command: $dependency"
done

RUN_DIR="$BASE_DIR/run"
LOG_DIR="$BASE_DIR/logs"
PID_FILE="$RUN_DIR/$APP_NAME.pid"
LOG_FILE="$LOG_DIR/$APP_NAME.log"
# A long-running viewer must never acquire/retain the process-control lock.
if [[ "$ACTION" == log ]]; then
    command -v tail >/dev/null || fail 'Missing command: tail (GNU coreutils is required for log).'
    [[ -f "$LOG_FILE" && -r "$LOG_FILE" ]] || fail "Log is not readable: $LOG_FILE"
    exec tail -n 100 -F -- "$LOG_FILE"
fi
mkdir -p -- "$RUN_DIR"
# The lock file must remain in place; unlinking it could allow separate locks.
exec 9>"$RUN_DIR/$APP_NAME.lock"
flock -w 10 9 || fail 'Another control operation is in progress; retry later.'
BOOT_ID=$(< /proc/sys/kernel/random/boot_id)
PID= TICKS= SAVED_BOOT=

# /proc/PID/stat field 22 is the start time. Strip the comm field first,
# since process names may contain spaces and closing parentheses.
process_info() {
    local stat
    local -a fields
    [[ -r /proc/$PID/stat ]] || return 1
    stat=$(cat -- "/proc/$PID/stat" 2>/dev/null) || return 1
    read -r -a fields <<< "${stat##*) }"
    [[ ${fields[0]:-Z} != Z && ${fields[0]:-X} != X ]] || return 1
    CURRENT_TICKS=${fields[19]:-}
    [[ "$CURRENT_TICKS" =~ ^[0-9]+$ ]]
}

load_pid() {
    [[ -f "$PID_FILE" ]] || return 1
    local extra
    read -r PID TICKS SAVED_BOOT extra < "$PID_FILE" || return 1
    [[ "$PID" =~ ^[1-9][0-9]*$ && "$PID" -gt 1 && "$TICKS" =~ ^[0-9]+$ && -z "$extra" ]]
}

is_running() {
    [[ "$SAVED_BOOT" == "$BOOT_ID" ]] && process_info &&
        [[ "$TICKS" == "$CURRENT_TICKS" ]] && kill -0 "$PID" 2>/dev/null
}

start_app() {
    if load_pid && is_running; then
        printf '%s already running (PID %s).\n' "$APP_NAME" "$PID"
        return 0
    fi
    rm -f -- "$PID_FILE"
    [[ "$JAR_FILE" = /* ]] || JAR_FILE="$BASE_DIR/$JAR_FILE"
    [[ -f "$JAR_FILE" && -r "$JAR_FILE" ]] || fail "JAR is not readable: $JAR_FILE"
    local java_bin
    if [[ -n "$JAVA_HOME" ]]; then
        java_bin="$JAVA_HOME/bin/java"
        [[ -x "$java_bin" ]] || fail "JAVA_HOME has no executable bin/java: $JAVA_HOME"
    else
        java_bin=$(command -v java) || fail 'Set JAVA_HOME or add java to PATH.'
    fi
    mkdir -p -- "$LOG_DIR"
    # Close the lock in the JVM child; otherwise stop/status would block forever.
    nohup "$java_bin" "${MEMORY_OPTS[@]}" "${GC_OPTS[@]}" \
        "${JAVA_OPTS[@]}" "${AGENT_OPTS[@]}" -jar "$JAR_FILE" "${APP_ARGS[@]}" \
        >>"$LOG_FILE" 2>&1 < /dev/null 9>&- &
    PID=$!
    if ! process_info; then
        wait "$PID" 2>/dev/null || true
        fail "Application exited immediately; inspect $LOG_FILE"
    fi
    TICKS=$CURRENT_TICKS
    SAVED_BOOT=$BOOT_ID
    # Persist before waiting so another invocation can recover the process.
    if ! printf '%s %s %s\n' "$PID" "$TICKS" "$BOOT_ID" > "$PID_FILE"; then
        if is_running; then kill -TERM "$PID" 2>/dev/null || true; fi
        fail 'Cannot write PID file.'
    fi
    sleep "$START_WAIT"
    if ! is_running; then
        rm -f -- "$PID_FILE"
        wait "$PID" 2>/dev/null || true
        fail "Application exited during startup; inspect $LOG_FILE"
    fi
    printf '%s started (PID %s). Log: %s\n' "$APP_NAME" "$PID" "$LOG_FILE"
}

stop_app() {
    if ! load_pid || ! is_running; then
        rm -f -- "$PID_FILE"
        printf '%s is stopped (stale PID file removed if present).\n' "$APP_NAME"
        return 0
    fi
    kill -TERM "$PID" 2>/dev/null || { if is_running; then fail "Cannot signal PID $PID"; fi; }
    local deadline=$((SECONDS + STOP_TIMEOUT))
    while is_running && (( SECONDS < deadline )); do sleep 0.2; done
    if is_running; then
        if [[ "$FORCE_STOP" == false ]]; then
            fail "Shutdown timed out; process and PID file retained. Set FORCE_STOP=true to allow SIGKILL."
        fi
        kill -KILL "$PID" 2>/dev/null || { if is_running; then fail "Cannot kill PID $PID"; fi; }
        deadline=$((SECONDS + 5))
        while is_running && (( SECONDS < deadline )); do sleep 0.2; done
        is_running && fail 'Process remains after SIGKILL; PID file retained.'
    fi
    rm -f -- "$PID_FILE"
    printf '%s stopped.\n' "$APP_NAME"
}

status_app() {
    local details
    if load_pid && is_running; then
        command -v ps >/dev/null || fail 'Missing command: ps (procps is required for status).'
        # Capture first, then recheck identity so a recycled PID is not reported.
        # -ww preserves long JVM options and application arguments without truncation.
        if details=$(LC_ALL=C ps -ww -p "$PID" \
            -o pid=PID -o ppid=PPID -o user=USER -o stat=STAT \
            -o lstart=STARTED -o etime=ELAPSED -o pcpu=CPU% \
            -o pmem=MEM% -o rss=RSS_KB -o args=COMMAND); then
            if is_running; then
                printf '%s is running (PID %s).\n%s\n' "$APP_NAME" "$PID" "$details"
                return 0
            fi
        elif is_running; then
            fail "Cannot read process information for PID $PID"
        fi
    fi
    printf '%s is stopped (PID file may be stale).\n' "$APP_NAME"
    return 3
}

case "$ACTION" in
    start) start_app ;;
    stop) stop_app ;;
    status|staus) status_app ;;
esac
