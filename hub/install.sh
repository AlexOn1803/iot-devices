#!/usr/bin/env bash
# IoT Devices — встановлення хаба на Raspberry Pi (Raspberry Pi OS / Debian).
#   curl -fsSL https://raw.githubusercontent.com/AlexOn1803/iot-devices/dev/hub/install.sh | sudo bash
# Повторний запуск оновлює хаб; налаштування (/etc/iot-hub) зберігаються.
#   IOTHUB_KIOSK=1|0   — панель на екрані Pi (типово питає, якщо екран під'єднано)
#   IOTHUB_REDEPLOY=1  — заново розгорнути ретранслятор Cloudflare
set -euo pipefail

BRANCH="${IOTHUB_BRANCH:-dev}"
RAW="https://raw.githubusercontent.com/AlexOn1803/iot-devices/$BRANCH"
DIR=/opt/iot-hub
CONF=/etc/iot-hub/config.json
CF_API=https://api.cloudflare.com/client/v4
APT() { DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@" >/dev/null; }

if [ "$(id -u)" -ne 0 ]; then echo "Запусти через sudo"; exit 1; fi
say() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }

say "Пакети системи"
apt-get update -qq
APT python3-venv python3-pip avahi-daemon curl jq mosquitto mosquitto-clients

say "Файли хаба"
id iothub >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin iothub
mkdir -p "$DIR/relay" "$DIR/web" /etc/iot-hub
for f in iothub.py rules.py mqttbridge.py web/index.html; do curl -fsSL "$RAW/hub/$f" -o "$DIR/$f"; done
curl -fsSL "$RAW/relay/src/index.js" -o "$DIR/relay/index.js"
[ -d "$DIR/venv" ] || python3 -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q --upgrade pip
"$DIR/venv/bin/pip" install -q --upgrade aiohttp tinytuya paho-mqtt
chown -R iothub:iothub "$DIR" /etc/iot-hub
chmod 700 /etc/iot-hub

say "Служба systemd"
cat > /etc/systemd/system/iot-hub.service <<EOF
[Unit]
Description=IoT Devices hub
After=network-online.target mosquitto.service
Wants=network-online.target

[Service]
User=iothub
Environment=IOTHUB_CONFIG=$CONF
ExecStart=$DIR/venv/bin/python $DIR/iothub.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# щоб додаток знаходив хаб у домашній мережі
cat > /etc/avahi/services/iot-hub.service <<'EOF'
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">IoT hub %h</name>
  <service><type>_iothub._tcp</type><port>8380</port></service>
</service-group>
EOF
systemctl reload avahi-daemon 2>/dev/null || systemctl restart avahi-daemon || true

systemctl daemon-reload
systemctl enable -q iot-hub
systemctl restart iot-hub
# перший запуск створює токен і PIN
for _ in $(seq 1 20); do [ -s "$CONF" ] && break; sleep 0.5; done
[ -s "$CONF" ] || { echo "Хаб не запустився — journalctl -u iot-hub -n 50"; exit 1; }

set_conf() {  # set_conf '<jq filter>' [--arg k v ...]
  local f="$1"; shift
  local t; t=$(mktemp)
  jq "$@" "$f" "$CONF" > "$t" && install -o iothub -g iothub -m 600 "$t" "$CONF"; rm -f "$t"
}

say "MQTT-брокер для плат ESP (Mosquitto на Pi)"
MQ_PASS=$(jq -r '.mqtt.pass // ""' "$CONF")
[ -n "$MQ_PASS" ] || MQ_PASS=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
mosquitto_passwd -b -c /etc/mosquitto/iot-hub.passwd iot "$MQ_PASS"
chown root:root /etc/mosquitto/iot-hub.passwd
chmod 600 /etc/mosquitto/iot-hub.passwd
cat > /etc/mosquitto/conf.d/iot-hub.conf <<'EOF'
# брокер для плат ESP у домашній мережі (лише з паролем)
listener 1883
allow_anonymous false
password_file /etc/mosquitto/iot-hub.passwd
EOF
systemctl enable -q mosquitto
systemctl restart mosquitto
systemctl stop iot-hub
set_conf '.mqtt = {"host":"127.0.0.1","port":1883,"user":"iot","pass":$p}' --arg p "$MQ_PASS"
systemctl start iot-hub
echo "Плати ESP хаб сам переналаштує на цей брокер (порт 1883)."

# нагадати PIN веб-панелі
cat > /usr/local/bin/iot-hub-pin <<EOF
#!/bin/sh
jq -r '"PIN веб-панелі: " + .pin' $CONF
EOF
chmod 755 /usr/local/bin/iot-hub-pin

# ---- панель на екрані Pi ----
HAS_SCREEN=0
for st in /sys/class/drm/*/status; do [ -f "$st" ] && grep -q '^connected' "$st" && HAS_SCREEN=1; done
KIOSK="${IOTHUB_KIOSK:-}"
if [ -z "$KIOSK" ]; then
  if [ -f /etc/systemd/system/iot-kiosk.service ] || [ -f /etc/xdg/autostart/iot-hub-kiosk.desktop ]; then KIOSK=1
  elif [ "$HAS_SCREEN" = 1 ]; then
    read -r -p "Показувати панель пристроїв на екрані Pi? [Y/n] " A < /dev/tty || A=n
    case "$A" in [nN]*) KIOSK=0 ;; *) KIOSK=1 ;; esac
  fi
fi
if [ "$KIOSK" = 1 ]; then
  say "Панель на екрані Pi"
  URL="http://localhost:8380/?kiosk=1"
  FLAGS="--kiosk --noerrdialogs --disable-infobars --no-first-run --password-store=basic --check-for-update-interval=31536000"
  command -v chromium >/dev/null || command -v chromium-browser >/dev/null || APT chromium 2>/dev/null || APT chromium-browser
  BR=$(command -v chromium || command -v chromium-browser)
  if command -v lxsession >/dev/null || command -v labwc >/dev/null || command -v wayfire >/dev/null; then
    # є робочий стіл — додати в автозапуск
    mkdir -p /etc/xdg/autostart
    cat > /etc/xdg/autostart/iot-hub-kiosk.desktop <<EOF
[Desktop Entry]
Type=Application
Name=IoT hub panel
Exec=sh -c "sleep 6; $BR $FLAGS $URL"
EOF
    echo "Панель відкриватиметься після входу на робочий стіл (перезавантаж Pi)."
  else
    # без робочого столу — мінімальний кіоск: cage + Chromium на tty1
    APT cage
    id iotkiosk >/dev/null 2>&1 || useradd -m -G video,render,input,audio -s /usr/sbin/nologin iotkiosk
    cat > /etc/pam.d/iot-kiosk <<'EOF'
auth    required pam_unix.so nullok
account required pam_unix.so
session required pam_unix.so
session required pam_systemd.so
EOF
    cat > /etc/systemd/system/iot-kiosk.service <<EOF
[Unit]
Description=IoT hub panel on the Pi screen
After=systemd-user-sessions.service iot-hub.service
Wants=iot-hub.service
Conflicts=getty@tty1.service

[Service]
User=iotkiosk
PAMName=iot-kiosk
TTYPath=/dev/tty1
TTYReset=yes
TTYVHangup=yes
TTYVTDisallocate=yes
StandardInput=tty-fail
UtmpIdentifier=tty1
UtmpMode=user
ExecStartPre=/bin/sleep 4
ExecStart=/usr/bin/cage -s -- $BR $FLAGS --ozone-platform=wayland $URL
Restart=always
RestartSec=5

[Install]
WantedBy=graphical.target
EOF
    systemctl daemon-reload
    systemctl set-default graphical.target >/dev/null 2>&1 || true
    systemctl disable -q getty@tty1 2>/dev/null || true
    systemctl enable -q iot-kiosk
    systemctl restart iot-kiosk || true
    echo "Панель запущена на екрані Pi (служба iot-kiosk)."
  fi
fi

# ---- доступ поза домом ----
RELAY=$(jq -r '.relay // ""' "$CONF" 2>/dev/null || echo "")
if [ -z "$RELAY" ] || [ "${IOTHUB_REDEPLOY:-}" = 1 ]; then
  say "Доступ поза домом (Cloudflare Worker, безкоштовно)"
  echo "Потрібен API-токен Cloudflare з правом «Edit Cloudflare Workers»:"
  echo "  dash.cloudflare.com → My Profile → API Tokens → Create Token → шаблон «Edit Cloudflare Workers»"
  echo "Токен використовується лише зараз і ніде не зберігається. Enter — пропустити (лише вдома)."
  echo "Вставка: права кнопка миші (у PuTTY/Windows Terminal) або Ctrl+Shift+V. Символи не відображаються — так і має бути."
  CF_TOKEN=""; ACC=""
  for TRY in 1 2 3; do
    read -r -s -p "API-токен: " CF_TOKEN < /dev/tty || CF_TOKEN=""
    echo
    CF_TOKEN=$(printf '%s' "$CF_TOKEN" | tr -d '[:space:]')
    [ -n "$CF_TOKEN" ] || break
    echo "Отримано символів: ${#CF_TOKEN}"
    if printf '%s' "$CF_TOKEN" | LC_ALL=C grep -q '[^A-Za-z0-9_-]'; then
      echo "У токені є зайві символи (інша розкладка?). Скопіюй його ще раз кнопкою копіювання в Cloudflare."
      continue
    fi
    H=(-H "Authorization: Bearer $CF_TOKEN")
    ACC=$(curl -sS "${H[@]}" "$CF_API/accounts" | jq -r '.result[0].id // empty' 2>/dev/null || true)
    [ -n "$ACC" ] && break
    echo "Cloudflare не прийняв токен (спроба $TRY з 3). Перевір, що скопійовано весь токен."
  done
  if [ -n "$CF_TOKEN" ] && [ -z "$ACC" ]; then
    echo "Пропускаю доступ поза домом. Повторити пізніше: curl -fsSL $RAW/hub/install.sh | sudo IOTHUB_REDEPLOY=1 bash"
    CF_TOKEN=""
  fi
  if [ -n "$CF_TOKEN" ]; then
    META='{"main_module":"index.js","compatibility_date":"2025-09-01","bindings":[{"type":"durable_object_namespace","name":"ROOM","class_name":"Room"}]'
    if ! curl -fsS "${H[@]}" "$CF_API/accounts/$ACC/workers/scripts/iot-hub/settings" >/dev/null 2>&1; then
      META="$META,\"migrations\":{\"new_tag\":\"v1\",\"new_sqlite_classes\":[\"Room\"]}"
    fi
    META="$META}"
    curl -fsS -X PUT "${H[@]}" "$CF_API/accounts/$ACC/workers/scripts/iot-hub" \
      -F "metadata=$META;type=application/json" \
      -F "index.js=@$DIR/relay/index.js;type=application/javascript+module" | jq -e '.success' >/dev/null
    curl -fsS -X POST "${H[@]}" -H "Content-Type: application/json" \
      "$CF_API/accounts/$ACC/workers/scripts/iot-hub/subdomain" -d '{"enabled":true,"previews_enabled":false}' >/dev/null
    SUB=$(curl -fsS "${H[@]}" "$CF_API/accounts/$ACC/workers/subdomain" | jq -r '.result.subdomain')
    RELAY="https://iot-hub.$SUB.workers.dev"
    systemctl stop iot-hub
    set_conf '.relay = $r' --arg r "$RELAY"
    systemctl start iot-hub
    echo "Ретранслятор: $RELAY"
  fi
fi

IP=$(hostname -I | awk '{print $1}')
say "Готово"
echo "Веб-панель:  http://$IP:8380   (з будь-якого пристрою вдома)"
jq -r '"PIN панелі:  " + .pin' "$CONF"
echo "Поза домом:  ${RELAY:-не налаштовано}"
echo
echo "Підключи додаток протягом 15 хвилин (телефон — у домашньому Wi-Fi):"
echo "  IoT Devices → Налаштування → Хаб Raspberry Pi → Знайти хаб"
echo "Якщо не знайде — введи адресу $IP"
echo "Відкрити підключення ще на 15 хв:  sudo systemctl kill -s USR1 iot-hub"
echo "Нагадати PIN панелі:                sudo iot-hub-pin"
echo "Журнал хаба:                        journalctl -u iot-hub -f"
