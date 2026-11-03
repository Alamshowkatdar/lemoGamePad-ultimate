#!/bin/sh
# lemoGamePad ultimate - one-shot Linux setup.
#
#   sudo sh pc/linux/install-linux.sh
#
# Works from a source checkout or next to a downloaded lemoGamePad-server.
#
# Installs everything the Linux receiver needs:
#   * distribution packages (Python 3, venv/pip, evdev build deps and a
#     Wayland screen-capture helper)
#   * the uinput kernel module, a udev rule and "input" group membership
#   * a Python virtualenv in pc/.venv with pc/requirements.txt
#   * firewall holes for UDP 9876 / TCP 9877 when ufw or firewalld is active
#   * a launcher in ~/.local/bin and the application menu
#
# Safe to rerun. install-uinput.sh is kept as an alias.
#
# Options:
#   --dry-run       show what would be done, change nothing
#   --no-packages   do not install distribution packages
#   --no-venv       do not create/update pc/.venv
#   --no-uinput     do not touch uinput, udev or groups
#   --no-firewall   do not open firewall ports
#   --no-shortcut   do not install the launcher/desktop entry
#   -h, --help      show this help
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

# Source checkout (pc/linux/) or a folder containing the release binary and
# this script.
if [ -f "$HERE/../vpad_server.py" ] && [ -f "$HERE/../requirements.txt" ]; then
    PC_DIR=$(dirname "$HERE")
    REPO_ROOT=$(dirname "$PC_DIR")
    HAS_SOURCE=1
else
    PC_DIR="$HERE"
    REPO_ROOT="$HERE"
    HAS_SOURCE=0
fi

VENV="$PC_DIR/.venv"
RULE="$HERE/70-lemogamepad.rules"

DRY_RUN=0
DO_PACKAGES=1
DO_VENV=1
DO_UINPUT=1
DO_FIREWALL=1
DO_SHORTCUT=1

usage() {
    awk 'NR == 1 { next }
         /^set -eu$/ { exit }
         { sub(/^# ?/, ""); print }' "$0"
}

# Re-run under sudo when a normal user starts the script. --help, --dry-run and
# a run where every privileged step is disabled stay unprivileged.
if [ "$(id -u)" -ne 0 ]; then
    needs_root=1
    no_packages=0
    no_uinput=0
    no_firewall=0
    for arg in "$@"; do
        case "$arg" in
            --dry-run|-h|--help) needs_root=0 ;;
            --no-packages) no_packages=1 ;;
            --no-uinput) no_uinput=1 ;;
            --no-firewall) no_firewall=1 ;;
        esac
    done
    if [ "$no_packages" -eq 1 ] && [ "$no_uinput" -eq 1 ] && [ "$no_firewall" -eq 1 ]; then
        needs_root=0
    fi
    if [ "$needs_root" -eq 1 ] && [ "${LEMO_SETUP_REEXEC:-0}" != 1 ] && command -v sudo >/dev/null 2>&1; then
        echo "Administrator rights are needed; re-running with sudo..."
        LEMO_SETUP_REEXEC=1
        export LEMO_SETUP_REEXEC
        exec sudo env \
            LEMO_SESSION_TYPE="${XDG_SESSION_TYPE:-}" \
            LEMO_DESKTOP="${XDG_CURRENT_DESKTOP:-${DESKTOP_SESSION:-}}" \
            sh "$0" "$@"
    fi
fi

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --no-packages) DO_PACKAGES=0 ;;
        --no-venv) DO_VENV=0 ;;
        --no-uinput) DO_UINPUT=0 ;;
        --no-firewall) DO_FIREWALL=0 ;;
        --no-shortcut) DO_SHORTCUT=0 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

if [ "$DRY_RUN" -eq 0 ] && [ "$(id -u)" -ne 0 ] && { [ "$DO_UINPUT" -eq 1 ] || [ "$DO_PACKAGES" -eq 1 ] || [ "$DO_FIREWALL" -eq 1 ]; }; then
    echo "Run this with sudo: sudo sh $0" >&2
    exit 1
fi

# ------------------------------------------------------------------ user info
TARGET_USER="${SUDO_USER:-}"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = root ]; then
    owner=$(stat -c %U "$REPO_ROOT" 2>/dev/null || true)
    if [ -n "$owner" ] && [ "$owner" != root ]; then
        TARGET_USER="$owner"
    fi
fi

if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != root ]; then
    TARGET_HOME=$(getent passwd "$TARGET_USER" 2>/dev/null | cut -d: -f6 || true)
    if [ -z "$TARGET_HOME" ]; then
        TARGET_HOME=$(awk -F: -v u="$TARGET_USER" '$1 == u { print $6 }' /etc/passwd 2>/dev/null || true)
    fi
    TARGET_GROUP=$(id -gn "$TARGET_USER" 2>/dev/null || echo "$TARGET_USER")
    if [ -z "$TARGET_HOME" ]; then
        echo "!! Could not find the home directory for $TARGET_USER." >&2
        echo "   Skipping the virtualenv and launcher; fix the passwd entry and rerun." >&2
        TARGET_HOME=/root
        DO_VENV=0
        DO_SHORTCUT=0
    fi
else
    TARGET_USER=root
    TARGET_HOME=/root
    TARGET_GROUP=root
    DO_VENV=0
    DO_SHORTCUT=0
    echo "!! Could not detect the desktop user." >&2
    echo "   Run this as: sudo sh $0  (from your normal account)" >&2
    echo "   System setup will continue; the venv and launcher are skipped." >&2
fi

as_user() {
    if [ "$TARGET_USER" = root ] || [ "$(id -un)" = "$TARGET_USER" ]; then
        "$@"
        return
    fi
    if command -v runuser >/dev/null 2>&1; then
        runuser -u "$TARGET_USER" -- env HOME="$TARGET_HOME" USER="$TARGET_USER" LOGNAME="$TARGET_USER" "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo -u "$TARGET_USER" env HOME="$TARGET_HOME" USER="$TARGET_USER" LOGNAME="$TARGET_USER" "$@"
    else
        echo "!! Neither runuser nor sudo is available; cannot run commands as $TARGET_USER." >&2
        return 1
    fi
}

# ------------------------------------------------------------- package manager
PM=""
if command -v pacman >/dev/null 2>&1; then
    PM=pacman
elif command -v apt-get >/dev/null 2>&1; then
    PM=apt
elif command -v dnf >/dev/null 2>&1; then
    PM=dnf
elif command -v zypper >/dev/null 2>&1; then
    PM=zypper
elif command -v apk >/dev/null 2>&1; then
    PM=apk
elif command -v xbps-install >/dev/null 2>&1; then
    PM=xbps
fi

run_pkg() {
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] $*"
        return 0
    fi
    "$@"
}

install_pkgs() {
    label="$1"
    shift
    [ "$#" -gt 0 ] || return 0
    echo "  $label: $*"
    case "$PM" in
        pacman) run_pkg pacman -S --needed --noconfirm "$@" ;;
        apt) run_pkg env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@" ;;
        dnf) run_pkg dnf install -y "$@" ;;
        zypper) run_pkg zypper --non-interactive install "$@" ;;
        apk) run_pkg apk add --no-cache "$@" ;;
        xbps) run_pkg xbps-install -Sy "$@" ;;
    esac
}

install_pkgs_optional() {
    if ! install_pkgs "$@"; then
        shift
        echo "  !! Could not install optional packages: $*"
        return 1
    fi
}

# ------------------------------------------------------------- capture helper
session_type="${LEMO_SESSION_TYPE:-${XDG_SESSION_TYPE:-}}"
desktop="${LEMO_DESKTOP:-${XDG_CURRENT_DESKTOP:-${DESKTOP_SESSION:-}}}"
if [ -z "$session_type" ] && command -v loginctl >/dev/null 2>&1; then
    session_type=$(loginctl show-user "$TARGET_USER" 2>/dev/null | sed -n 's/^Type=//p' | head -n 1 || true)
fi

CAPTURE_PKG=""
if [ "$session_type" = wayland ]; then
    case "$desktop" in
        *KDE*|*Plasma*|*plasma*) CAPTURE_PKG=spectacle ;;
        *GNOME*|*gnome*|*Unity*) CAPTURE_PKG=gnome-screenshot ;;
        *) CAPTURE_PKG=grim ;;
    esac
fi

# ---------------------------------------------------------------- distro pkgs
if [ "$DO_PACKAGES" -eq 1 ]; then
    echo "==> Packages"
    if [ -z "$PM" ]; then
        echo "  !! No supported package manager found."
        echo "     Install Python 3 (with venv and pip), gcc, the libevdev headers"
        echo "     and (on Wayland) grim, spectacle or gnome-screenshot."
    else
        case "$PM" in
            pacman)
                BASE_PKGS="python python-pip gcc pkgconf libevdev"
                ;;
            apt)
                BASE_PKGS="python3 python3-venv python3-pip python3-dev gcc libevdev-dev pkg-config linux-libc-dev"
                run_pkg apt-get update -qq || true
                ;;
            dnf)
                BASE_PKGS="python3 python3-pip python3-devel gcc libevdev-devel pkgconf kernel-headers"
                ;;
            zypper)
                BASE_PKGS="python3 python3-pip python3-devel gcc libevdev-devel pkg-config"
                run_pkg zypper --non-interactive refresh || true
                ;;
            apk)
                BASE_PKGS="python3 py3-pip python3-dev gcc musl-dev libevdev-dev pkgconf"
                ;;
            xbps)
                BASE_PKGS="python3 python3-pip python3-devel gcc pkg-config libevdev-devel"
                ;;
        esac

        if [ "$HAS_SOURCE" -eq 0 ]; then
            BASE_PKGS=""
            echo "  prebuilt receiver detected; Python build packages are not needed."
        fi

        if ! install_pkgs "base packages" $BASE_PKGS; then
            echo "!! Required packages could not be installed." >&2
            case "$PM" in
                pacman) echo "   Try: sudo pacman -Syu   then rerun this script." >&2 ;;
                apt) echo "   Try: sudo apt-get update   then rerun this script." >&2 ;;
            esac
            exit 1
        fi

        if [ -n "$CAPTURE_PKG" ]; then
            install_pkgs_optional "Wayland capture helper ($session_type)" "$CAPTURE_PKG" || true
            # grim is the safest fallback for unknown wlroots compositors.
            if [ "$CAPTURE_PKG" != grim ]; then
                install_pkgs_optional "Wayland capture fallback" grim || true
            fi
            if [ -z "$desktop" ] && [ "$CAPTURE_PKG" = grim ]; then
                echo "  Desktop not detected; installed grim."
                echo "  If the phone's screen preview stays black, install spectacle (KDE)"
                echo "  or gnome-screenshot (GNOME) and rerun this script."
            fi
        fi
    fi
else
    echo "==> Packages (skipped)"
fi

# --------------------------------------------------------------------- uinput
NEED_RELOGIN=0
if [ "$DO_UINPUT" -eq 1 ]; then
    echo "==> uinput and permissions"

    # The release ships this script without the separate rules file, so fall
    # back to an embedded copy of the same rule.
    RULE_SRC="$RULE"
    RULE_TMP=""
    if [ ! -f "$RULE_SRC" ]; then
        if [ "$DRY_RUN" -eq 1 ]; then
            RULE_SRC="(built-in udev rule)"
        else
            RULE_TMP=$(mktemp "${TMPDIR:-/tmp}/lemogamepad.XXXXXX")
            cat > "$RULE_TMP" <<'RULE_EOF'
# lemoGamePad ultimate: let the active desktop user create virtual gamepads.
# The 70- prefix matters: it must run before systemd's 71-seat.rules.
KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="input", MODE="0660", TAG+="uaccess", OPTIONS+="static_node=uinput"
RULE_EOF
            RULE_SRC="$RULE_TMP"
        fi
    fi

    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] modprobe uinput"
        echo "  [dry-run] install $RULE_SRC -> /etc/udev/rules.d/70-lemogamepad.rules"
        echo "  [dry-run] add $TARGET_USER to the input group"
    else
        if command -v modprobe >/dev/null 2>&1; then
            modprobe uinput 2>/dev/null || true
        fi
        printf 'uinput\n' > /etc/modules-load.d/lemogamepad.conf

        if command -v groupadd >/dev/null 2>&1; then
            groupadd -f input 2>/dev/null || true
        elif command -v addgroup >/dev/null 2>&1; then
            grep -q '^input:' /etc/group 2>/dev/null || addgroup -S input 2>/dev/null || true
        fi

        # Kernels with uinput built in still need the device node.
        if [ ! -e /dev/uinput ]; then
            mknod /dev/uinput c 10 223 2>/dev/null || true
        fi
        if [ ! -e /dev/uinput ]; then
            echo "!! /dev/uinput is missing." >&2
            echo "   Your kernel needs CONFIG_INPUT_UINPUT (most distributions enable it)." >&2
            exit 1
        fi

        install -D -m 0644 "$RULE_SRC" /etc/udev/rules.d/70-lemogamepad.rules
        if [ -n "$RULE_TMP" ]; then
            rm -f "$RULE_TMP"
        fi
        # Remove the rule used by older versions: it ran after systemd's
        # 71-seat.rules, so /dev/uinput never received the session ACL.
        rm -f /etc/udev/rules.d/99-lemogamepad.rules

        if command -v udevadm >/dev/null 2>&1; then
            udevadm control --reload-rules 2>/dev/null || true
            udevadm trigger --name-match=uinput 2>/dev/null || true
            udevadm settle 2>/dev/null || true
        fi

        # Apply the permissions immediately as well, so this login can test it.
        chgrp input /dev/uinput 2>/dev/null || true
        chmod 0660 /dev/uinput 2>/dev/null || true

        GROUP_ADDED=0
        if [ "$TARGET_USER" != root ]; then
            if id -nG "$TARGET_USER" 2>/dev/null | tr ' ' '\n' | grep -qx input; then
                echo "  $TARGET_USER is already in the input group."
            else
                if command -v usermod >/dev/null 2>&1; then
                    usermod -aG input "$TARGET_USER"
                elif command -v adduser >/dev/null 2>&1; then
                    adduser "$TARGET_USER" input 2>/dev/null || true
                fi
                GROUP_ADDED=1
            fi
        fi

        # Do not use "test -w" here: the installer runs as root, and root
        # capabilities make a runuser-based test report a false success.
        # The uaccess ACL is the real proof that the active session can open
        # the device without logging out.
        CAN_WRITE=0
        if [ "$TARGET_USER" != root ]; then
            if command -v getfacl >/dev/null 2>&1 &&
               getfacl -p /dev/uinput 2>/dev/null | grep -qx "user:$TARGET_USER:rw-"; then
                CAN_WRITE=1
            elif [ "$GROUP_ADDED" -eq 0 ] &&
                 [ "$(stat -c '%G %a' /dev/uinput 2>/dev/null)" = "input 660" ]; then
                # Already in the input group in this session, so group access
                # applies even if the ACL was not written (no systemd seat).
                CAN_WRITE=1
            fi
        fi

        if [ "$TARGET_USER" != root ]; then
            if [ "$CAN_WRITE" -eq 1 ]; then
                NEED_RELOGIN=0
                echo "  /dev/uinput is writable for $TARGET_USER now."
            else
                NEED_RELOGIN=1
            fi
        fi
    fi
else
    echo "==> uinput and permissions (skipped)"
fi

# ---------------------------------------------------------------- python venv
if [ "$DO_VENV" -eq 1 ] && [ "$HAS_SOURCE" -eq 0 ]; then
    echo "==> Python virtualenv (not needed for the prebuilt receiver)"
elif [ "$DO_VENV" -eq 1 ]; then
    echo "==> Python virtualenv ($VENV)"
    PYTHON="${PYTHON:-python3}"
    if ! command -v "$PYTHON" >/dev/null 2>&1; then
        echo "!! $PYTHON not found." >&2
        exit 1
    fi

    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] $PYTHON -m venv $VENV"
        echo "  [dry-run] $VENV/bin/python -m pip install -r $PC_DIR/requirements.txt"
        echo "  [dry-run] verify evdev, mss, pillow and qrcode"
    else
        if [ -e "$VENV" ] && [ ! -x "$VENV/bin/python" ]; then
            echo "  removing an incomplete virtualenv"
            rm -rf "$VENV"
        fi
        if [ ! -x "$VENV/bin/python" ]; then
            if as_user test -w "$PC_DIR" 2>/dev/null; then
                as_user "$PYTHON" -m venv "$VENV"
            else
                "$PYTHON" -m venv "$VENV"
            fi
        fi
        chown -R "$TARGET_USER:$TARGET_GROUP" "$VENV" 2>/dev/null || true

        echo "  installing Python requirements (this can take a minute)"
        as_user "$VENV/bin/python" -m pip install \
            --disable-pip-version-check --no-input \
            -r "$PC_DIR/requirements.txt"

        as_user "$VENV/bin/python" -c \
            'import evdev, mss, qrcode; from PIL import Image; print("  evdev, mss, pillow, qrcode: OK")'
    fi
else
    echo "==> Python virtualenv (skipped)"
fi

# ------------------------------------------------------------------ firewall
if [ "$DO_FIREWALL" -eq 1 ]; then
    echo "==> Firewall"
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] open UDP 9876 and TCP 9877 when ufw/firewalld is active"
    elif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi '^Status: active'; then
        ufw allow 9876/udp >/dev/null 2>&1 || true
        ufw allow 9877/tcp >/dev/null 2>&1 || true
        echo "  ufw: allowed UDP 9876 and TCP 9877."
    elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --permanent --add-port=9876/udp >/dev/null 2>&1 || true
        firewall-cmd --permanent --add-port=9877/tcp >/dev/null 2>&1 || true
        firewall-cmd --reload >/dev/null 2>&1 || true
        echo "  firewalld: allowed UDP 9876 and TCP 9877."
    else
        echo "  No active ufw/firewalld detected; nothing to open."
        echo "  If a phone cannot reach the PC, allow UDP 9876 and TCP 9877."
    fi
else
    echo "==> Firewall (skipped)"
fi

# ------------------------------------------------------------------ launchers
START_CMD=""
for candidate in \
    "$HERE/run.sh" \
    "$HERE/lemoGamePad-server" \
    "$PC_DIR/lemoGamePad-server" \
    "$PC_DIR/dist/lemoGamePad-server" \
    "$HERE/dist/lemoGamePad-server"
do
    if [ -f "$candidate" ]; then
        if [ "$DRY_RUN" -eq 0 ] && [ ! -x "$candidate" ]; then
            chmod +x "$candidate" 2>/dev/null || true
        fi
        if [ -x "$candidate" ] || [ "$DRY_RUN" -eq 1 ]; then
            START_CMD="$candidate"
            break
        fi
    fi
done

if [ "$DO_SHORTCUT" -eq 1 ]; then
    echo "==> Launcher"
    BIN_DIR="$TARGET_HOME/.local/bin"
    APP_DIR="$TARGET_HOME/.local/share/applications"

    if [ -z "$START_CMD" ]; then
        echo "  No receiver found next to this script; skipping the launcher."
        echo "  Put lemoGamePad-server in the same folder and rerun."
    elif [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] ln -s $START_CMD $BIN_DIR/lemogamepad"
        echo "  [dry-run] write $APP_DIR/lemogamepad.desktop"
    else
        install -d -m 0755 "$BIN_DIR" "$APP_DIR"
        chown "$TARGET_USER:$TARGET_GROUP" "$BIN_DIR" "$APP_DIR" 2>/dev/null || true
        ln -sfn "$START_CMD" "$BIN_DIR/lemogamepad"
        chown -h "$TARGET_USER:$TARGET_GROUP" "$BIN_DIR/lemogamepad" 2>/dev/null || true

        cat > "$APP_DIR/lemogamepad.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=lemoGamePad ultimate
Comment=Virtual Xbox 360 / DualShock 4 gamepad receiver
Exec="$BIN_DIR/lemogamepad"
Icon=input-gaming
Terminal=true
Categories=Game;Utility;
StartupNotify=false
EOF
        chown "$TARGET_USER:$TARGET_GROUP" "$APP_DIR/lemogamepad.desktop" 2>/dev/null || true
        if command -v update-desktop-database >/dev/null 2>&1; then
            update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
        fi
        echo "  installed $BIN_DIR/lemogamepad and the application-menu entry."
    fi
else
    echo "==> Launcher (skipped)"
fi

# --------------------------------------------------------------------- summary
echo
if [ "$DRY_RUN" -eq 1 ]; then
    echo "Dry run complete. Nothing was changed."
    exit 0
fi

echo "Done."
echo
if [ "$NEED_RELOGIN" -eq 1 ]; then
    echo "Log out and back in once so the new 'input' group applies."
    echo "Until then you can still start the server; it will say uinput is not ready."
    echo
fi
echo "Start the receiver:"
if [ -n "$START_CMD" ]; then
    echo "  $START_CMD"
else
    echo "  Place lemoGamePad-server next to install-linux.sh and run it there."
fi
if [ "$DO_SHORTCUT" -eq 1 ] && [ -n "$START_CMD" ]; then
    echo "or choose 'lemoGamePad ultimate' from the application menu."
fi
echo
echo "Then scan the QR code in the terminal with the Android app."
